/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.core.function.supplier;

import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.streams.core.common.Constant;
import org.apache.rocketmq.streams.core.exception.RecoverStateStoreThrowable;
import org.apache.rocketmq.streams.core.function.ValueJoinAction;
import org.apache.rocketmq.streams.core.metadata.Data;
import org.apache.rocketmq.streams.core.running.AbstractWindowProcessor;
import org.apache.rocketmq.streams.core.running.Processor;
import org.apache.rocketmq.streams.core.running.StreamContext;
import org.apache.rocketmq.streams.core.window.JoinType;
import org.apache.rocketmq.streams.core.window.StreamType;
import org.apache.rocketmq.streams.core.window.Window;
import org.apache.rocketmq.streams.core.window.WindowInfo;
import org.apache.rocketmq.streams.core.window.WindowKey;
import org.apache.rocketmq.streams.core.window.WindowState;
import org.apache.rocketmq.streams.core.window.WindowStore;
import org.apache.rocketmq.streams.core.util.Pair;
import org.apache.rocketmq.streams.core.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Supplier;

public class JoinWindowAggregateSupplier<K, V1, V2, OUT> implements Supplier<Processor<? super OUT>> {
    private static final Logger logger = LoggerFactory.getLogger(JoinWindowAggregateSupplier.class.getName());

    private String name;
    private WindowInfo windowInfo;
    private final ValueJoinAction<V1, V2, OUT> joinAction;
    private JoinType joinType;

    public JoinWindowAggregateSupplier(String name, WindowInfo windowInfo, ValueJoinAction<V1, V2, OUT> joinAction) {
        this.name = name;
        this.windowInfo = windowInfo;
        this.joinType = windowInfo.getJoinStream().getJoinType();
        this.joinAction = joinAction;
    }

    @Override
    public Processor<Object> get() {
        return new JoinStreamWindowAggregateProcessor(name, windowInfo, joinType, joinAction);
    }


    @SuppressWarnings("unchecked")
    private class JoinStreamWindowAggregateProcessor extends AbstractWindowProcessor<Object> {
        private String name;
        private final WindowInfo windowInfo;
        private final JoinType joinType;
        private ValueJoinAction<V1, V2, OUT> joinAction;
        private MessageQueue stateTopicMessageQueue;
        private WindowStore<K, V1> leftWindowStore;
        private WindowStore<K, V2> rightWindowStore;

        public JoinStreamWindowAggregateProcessor(String name, WindowInfo windowInfo, JoinType joinType, ValueJoinAction<V1, V2, OUT> joinAction) {
            this.name = Utils.buildKey(name, JoinStreamWindowAggregateProcessor.class.getSimpleName());
            this.windowInfo = windowInfo;
            this.joinType = joinType;
            this.joinAction = joinAction;
        }

        @Override
        public void preProcess(StreamContext<Object> context) throws RecoverStateStoreThrowable {
            super.preProcess(context);
            leftWindowStore = new WindowStore<>(super.waitStateReplay(), WindowState::byte2WindowState, WindowState::windowState2Byte);
            rightWindowStore = new WindowStore<>(super.waitStateReplay(), WindowState::byte2WindowState, WindowState::windowState2Byte);
            String stateTopicName = getSourceTopic() + Constant.STATE_TOPIC_SUFFIX;
            this.stateTopicMessageQueue = new MessageQueue(stateTopicName, getSourceBrokerName(), getSourceQueueId());
        }


        @Override
        public void process(Object data) throws Throwable {

            Object key = this.context.getKey();
            long time = this.context.getDataTime();
            Properties header = this.context.getHeader();
            long watermark = this.context.getWatermark();
            WindowInfo.JoinStream stream = (WindowInfo.JoinStream) header.get(Constant.STREAM_TAG);

            if (time < watermark) {
                //已经触发，丢弃数据
                return;
            }

            StreamType streamType = stream.getStreamType();


            store(key, data, time, streamType);

            fire(watermark, streamType);
        }


        private void store(Object key, Object data, long time, StreamType streamType) throws Throwable {
            String name = Utils.buildKey(this.name, streamType.name());
            List<Window> windows = super.calculateWindow(windowInfo, time);
            for (Window window : windows) {
                logger.debug("timestamp=" + time + ". time -> window: " + Utils.format(time) + "->" + window);

                WindowKey windowKey = new WindowKey(name, super.toHexString(key), window.getEndTime(), window.getStartTime());

                switch (streamType) {
                    case LEFT_STREAM:
                        WindowState<K, V1> leftState = new WindowState<>((K) key, (V1) data, time);
                        this.leftWindowStore.put(stateTopicMessageQueue, windowKey, leftState);
                        break;
                    case RIGHT_STREAM:
                        WindowState<K, V2> rightState = new WindowState<>((K) key, (V2) data, time);
                        this.rightWindowStore.put(stateTopicMessageQueue, windowKey, rightState);
                        break;
                }
            }
        }

        private void fire(long watermark, StreamType streamType) throws Throwable {
            String leftWindow = Utils.buildKey(this.name, StreamType.LEFT_STREAM.name());
            WindowKey leftWindowKey = new WindowKey(leftWindow, null, watermark, 0L);
            List<Pair<WindowKey, WindowState<K, V1>>> leftPairs = this.leftWindowStore.searchLessThanWatermark(leftWindowKey);

            String rightWindow = Utils.buildKey(this.name, StreamType.RIGHT_STREAM.name());
            WindowKey rightWindowKey = new WindowKey(rightWindow, null, watermark, 0L);
            List<Pair<WindowKey, WindowState<K, V2>>> rightPairs = this.rightWindowStore.searchLessThanWatermark(rightWindowKey);


            if (leftPairs.size() == 0 && rightPairs.size() == 0) {
                return;
            }

            leftPairs.sort(Comparator.comparing(pair -> {
                WindowKey key = pair.getKey();
                return key.getWindowEnd();
            }));
            rightPairs.sort(Comparator.comparing(pair -> {
                WindowKey key = pair.getKey();
                return key.getWindowEnd();
            }));

            switch (joinType) {
                case INNER_JOIN:
                    //匹配上才触发
                    for (Pair<WindowKey, WindowState<K, V1>> leftPair : leftPairs) {
                        String leftPrefix = leftPair.getKey().getKeyAndWindow();

                        for (Pair<WindowKey, WindowState<K, V2>> rightPair : rightPairs) {
                            String rightPrefix = rightPair.getKey().getKeyAndWindow();

                            //相同window中相同key，聚合
                            if (leftPrefix.equals(rightPrefix)) {
                                //do fire
                                V1 o1 = leftPair.getValue().getValue();
                                V2 o2 = rightPair.getValue().getValue();

                                OUT out = this.joinAction.apply(o1, o2);

                                Properties header = this.context.getHeader();
                                header.put(Constant.WINDOW_START_TIME, leftPair.getKey().getWindowStart());
                                header.put(Constant.WINDOW_END_TIME, leftPair.getKey().getWindowEnd());
                                Data<K, OUT> result = new Data<>(this.context.getKey(), out, this.context.getDataTime(), header);
                                Data<K, Object> convert = super.convert(result);
                                this.context.forward(convert);
                            }
                        }
                    }
                    break;
                case LEFT_JOIN:
                    switch (streamType) {
                        case LEFT_STREAM:
                            //左流全部触发，不管右流匹配上没
                            for (Pair<WindowKey, WindowState<K, V1>> leftPair : leftPairs) {
                                String leftPrefix = leftPair.getKey().getKeyAndWindow();
                                Pair<WindowKey, WindowState<K, V2>> targetPair = null;
                                for (Pair<WindowKey, WindowState<K, V2>> rightPair : rightPairs) {
                                    if (rightPair.getKey().getKeyAndWindow().equals(leftPrefix)) {
                                        targetPair = rightPair;
                                        break;
                                    }
                                }

                                //fire
                                V1 o1 = leftPair.getValue().getValue();
                                V2 o2 = null;
                                if (targetPair != null) {
                                    o2 = targetPair.getValue().getValue();
                                }

                                OUT out = this.joinAction.apply(o1, o2);
                                Properties header = this.context.getHeader();
                                header.put(Constant.WINDOW_START_TIME, leftPair.getKey().getWindowStart());
                                header.put(Constant.WINDOW_END_TIME, leftPair.getKey().getWindowEnd());
                                Data<K, OUT> result = new Data<>(this.context.getKey(), out, this.context.getDataTime(), header);
                                Data<K, Object> convert = super.convert(result);
                                this.context.forward(convert);
                            }
                            break;
                        case RIGHT_STREAM:
                            //do nothing.
                    }
                    break;
            }

            //删除状态
            for (Pair<WindowKey, WindowState<K, V1>> leftPair : leftPairs) {
                this.leftWindowStore.deleteByKey(leftPair.getKey());
            }

            for (Pair<WindowKey, WindowState<K, V2>> rightPair : rightPairs) {
                this.rightWindowStore.deleteByKey(rightPair.getKey());
            }
        }

    }
}
