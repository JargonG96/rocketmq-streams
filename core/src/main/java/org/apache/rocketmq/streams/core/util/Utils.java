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
package org.apache.rocketmq.streams.core.util;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.streams.core.common.Constant;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Utils {
    public static final String pattern = "%s@%s@%s";

    public static String buildKey(String brokerName, String topic, int queueId) {
        return String.format(pattern, brokerName, topic, queueId);
    }

    public static String buildKey(String key, String... args) {
        if (StringUtils.isEmpty(key)) {
            return null;
        }

        StringBuilder builder = new StringBuilder();
        builder.append(key);

        if (args != null && args.length != 0) {
            builder.append(Constant.SPLIT);
            for (String arg : args) {
                builder.append(arg);
                builder.append(Constant.SPLIT);
            }
        }

        return builder.substring(0, builder.lastIndexOf(Constant.SPLIT));
    }

    public static String[] split(String source) {
        if (StringUtils.isEmpty(source)) {
            return new String[]{};
        }

        return source.split(Constant.SPLIT);
    }

    public static byte[] object2Byte(Object target) {
        if (target == null) {
            return new byte[]{};
        }

        return JSON.toJSONBytes(target, SerializerFeature.WriteClassName);
    }


    public static <B> B byte2Object(byte[] source, Class<B> clazz) {
        if (source == null || source.length ==0) {
            return null;
        }

        return JSON.parseObject(source, clazz);
    }

    public static SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    public static String format(long timestamp) {
        Date date = new Date(timestamp);
        return df.format(date);
    }
}
