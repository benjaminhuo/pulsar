/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.windowing;

import com.google.gson.Gson;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.apache.pulsar.functions.api.Context;
import org.apache.pulsar.functions.api.Function;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.utils.Reflections;
import org.apache.pulsar.common.functions.WindowConfig;
import org.apache.pulsar.functions.windowing.evictors.CountEvictionPolicy;
import org.apache.pulsar.functions.windowing.evictors.TimeEvictionPolicy;
import org.apache.pulsar.functions.windowing.evictors.WatermarkCountEvictionPolicy;
import org.apache.pulsar.functions.windowing.evictors.WatermarkTimeEvictionPolicy;
import org.apache.pulsar.functions.windowing.triggers.CountTriggerPolicy;
import org.apache.pulsar.functions.windowing.triggers.TimeTriggerPolicy;
import org.apache.pulsar.functions.windowing.triggers.WatermarkCountTriggerPolicy;
import org.apache.pulsar.functions.windowing.triggers.WatermarkTimeTriggerPolicy;

import net.jodah.typetools.TypeResolver;

@Slf4j
public class WindowFunctionExecutor<I, O> implements Function<I, O> {

    private boolean initialized;
    protected WindowConfig windowConfig;
    private WindowManager<I> windowManager;
    private TimestampExtractor<I> timestampExtractor;
    protected transient WaterMarkEventGenerator<I> waterMarkEventGenerator;

    protected static final long DEFAULT_MAX_LAG_MS = 0; // no lag
    protected static final long DEFAULT_WATERMARK_EVENT_INTERVAL_MS = 1000; // 1s

    protected java.util.function.Function<Collection<I>, O> windowFunction;

    public void initialize(Context context) {
        this.windowConfig = this.getWindowConfigs(context);
        this.windowFunction = intializeUserFunction(this.windowConfig);
        log.info("Window Config: {}", this.windowConfig);
        this.windowManager = this.getWindowManager(this.windowConfig, context);
        this.initialized = true;
        this.start();
    }

    private java.util.function.Function<Collection<I>, O> intializeUserFunction(WindowConfig windowConfig) {
        String actualWindowFunctionClassName = windowConfig.getActualWindowFunctionClassName();
        ClassLoader clsLoader = Thread.currentThread().getContextClassLoader();
        Object userClassObject = Reflections.createInstance(
                actualWindowFunctionClassName,
                clsLoader);
        if (userClassObject instanceof java.util.function.Function) {
            Class<?>[] typeArgs = TypeResolver.resolveRawArguments(
                    java.util.function.Function.class, userClassObject.getClass());
            if (typeArgs[0].equals(Collection.class)) {
                return (java.util.function.Function) userClassObject;
            } else {
                throw new IllegalArgumentException("Window function must take a collection as input");
            }
        } else {
            throw new IllegalArgumentException("Window function does not implement the correct interface");
        }
    }

    private WindowConfig getWindowConfigs(Context context) {

        if (!context.getUserConfigValue(WindowConfig.WINDOW_CONFIG_KEY).isPresent()) {
            throw new IllegalArgumentException("Window Configs cannot be found");
        }
        WindowConfig windowConfig = new Gson().fromJson(
                (new Gson().toJson(context.getUserConfigValue(WindowConfig.WINDOW_CONFIG_KEY).get())),
                WindowConfig.class);


        WindowUtils.inferDefaultConfigs(windowConfig);
        return windowConfig;
    }

    private WindowManager<I> getWindowManager(WindowConfig windowConfig, Context context) {

        WindowLifecycleListener<Event<I>> lifecycleListener = newWindowLifecycleListener(context);
        WindowManager<I> manager = new WindowManager<>(lifecycleListener, new ConcurrentLinkedQueue<>());

        if (this.windowConfig.getTimestampExtractorClassName() != null) {
            this.timestampExtractor = getTimeStampExtractor(windowConfig);

            waterMarkEventGenerator = new WaterMarkEventGenerator<>(manager, this.windowConfig
                    .getWatermarkEmitIntervalMs(),
                    this.windowConfig.getMaxLagMs(), new HashSet<>(context.getInputTopics()), context);
        } else {
            if (this.windowConfig.getLateDataTopic() != null) {
                throw new IllegalArgumentException(
                        "Late data topic can be defined only when specifying a timestamp extractor class");
            }
        }

        EvictionPolicy<I, ?> evictionPolicy = getEvictionPolicy(windowConfig);
        TriggerPolicy<I, ?> triggerPolicy = getTriggerPolicy(windowConfig, manager,
                evictionPolicy, context);
        manager.setEvictionPolicy(evictionPolicy);
        manager.setTriggerPolicy(triggerPolicy);

        return manager;
    }

    private TimestampExtractor<I> getTimeStampExtractor(WindowConfig windowConfig) {

        Class<?> theCls;
        try {
            theCls = Class.forName(windowConfig.getTimestampExtractorClassName(),
                    true, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException cnfe) {
            throw new RuntimeException(
                    String.format("Timestamp extractor class %s must be in class path",
                            windowConfig.getTimestampExtractorClassName()), cnfe);
        }

        Object result;
        try {
            Constructor<?> constructor = theCls.getDeclaredConstructor();
            constructor.setAccessible(true);
            result = constructor.newInstance();
        } catch (InstantiationException ie) {
            throw new RuntimeException("User class must be concrete", ie);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("User class doesn't have such method", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("User class must have a no-arg constructor", e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException("User class constructor throws exception", e);
        }
        Class<?>[] timestampExtractorTypeArgs = TypeResolver.resolveRawArguments(
                TimestampExtractor.class, result.getClass());
        Class<?>[] typeArgs = TypeResolver.resolveRawArguments(Function.class, this.getClass());
        if (!typeArgs[0].equals(timestampExtractorTypeArgs[0])) {
            throw new RuntimeException(
                    "Inconsistent types found between function input type and timestamp extractor type: "
                            + " function type = " + typeArgs[0] + ", timestamp extractor type = "
                            + timestampExtractorTypeArgs[0]);
        }
        return (TimestampExtractor<I>) result;
    }

    private TriggerPolicy<I, ?> getTriggerPolicy(WindowConfig windowConfig, WindowManager<I> manager,
                                                 EvictionPolicy<I, ?> evictionPolicy, Context context) {
        if (windowConfig.getSlidingIntervalCount() != null) {
            if (this.isEventTime()) {
                return new WatermarkCountTriggerPolicy<>(
                        windowConfig.getSlidingIntervalCount(), manager, evictionPolicy, manager);
            } else {
                return new CountTriggerPolicy<>(windowConfig.getSlidingIntervalCount(), manager, evictionPolicy);
            }
        } else {
            if (this.isEventTime()) {
                return new WatermarkTimeTriggerPolicy<>(windowConfig.getSlidingIntervalDurationMs(), manager,
                        evictionPolicy, manager);
            }
            return new TimeTriggerPolicy<>(windowConfig.getSlidingIntervalDurationMs(), manager,
                    evictionPolicy, context);
        }
    }

    private EvictionPolicy<I, ?> getEvictionPolicy(WindowConfig windowConfig) {
        if (windowConfig.getWindowLengthCount() != null) {
            if (this.isEventTime()) {
                return new WatermarkCountEvictionPolicy<>(windowConfig.getWindowLengthCount());
            } else {
                return new CountEvictionPolicy<>(windowConfig.getWindowLengthCount());
            }
        } else {
            if (this.isEventTime()) {
                return new WatermarkTimeEvictionPolicy<>(
                        windowConfig.getWindowLengthDurationMs(), windowConfig.getMaxLagMs());
            } else {
                return new TimeEvictionPolicy<>(windowConfig.getWindowLengthDurationMs());
            }
        }
    }

    protected WindowLifecycleListener<Event<I>> newWindowLifecycleListener(Context context) {
        return new WindowLifecycleListener<Event<I>>() {
            @Override
            public void onExpiry(List<Event<I>> events) {
                for (Event<I> event : events) {
                    event.getRecord().ack();
                }
            }

            @Override
            public void onActivation(List<Event<I>> tuples, List<Event<I>> newTuples, List<Event<I>>
                    expiredTuples, Long referenceTime) {
                processWindow(
                        context,
                        tuples.stream().map(event -> event.get()).collect(Collectors.toList()),
                        newTuples.stream().map(event -> event.get()).collect(Collectors.toList()),
                        expiredTuples.stream().map(event -> event.get()).collect(Collectors.toList()),
                        referenceTime);
            }
        };
    }

    private void processWindow(Context context, List<I> tuples, List<I> newTuples, List<I>
            expiredTuples, Long referenceTime) {

        O output = null;
        try {
            output = this.process(
                    new WindowImpl<>(tuples, newTuples, expiredTuples, getWindowStartTs(referenceTime), referenceTime),
                    new WindowContextImpl(context));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (output != null) {
            context.publish(context.getOutputTopic(), output, context.getOutputSchemaType());
        }
    }

    private Long getWindowStartTs(Long endTs) {
        Long res = null;
        if (endTs != null && this.windowConfig.getWindowLengthDurationMs() != null) {
            res = endTs - this.windowConfig.getWindowLengthDurationMs();
        }
        return res;
    }

    private void start() {
        if (this.waterMarkEventGenerator != null) {
            log.debug("Starting waterMarkEventGenerator");
            this.waterMarkEventGenerator.start();
        }

        log.debug("Starting trigger policy");
        this.windowManager.triggerPolicy.start();
    }

    public void shutdown() {
        if (this.waterMarkEventGenerator != null) {
            this.waterMarkEventGenerator.shutdown();
        }
        if (this.windowManager != null) {
            this.windowManager.shutdown();
        }
    }

    private boolean isEventTime() {
        return this.timestampExtractor != null;
    }

    @Override
    public O process(I input, Context context) throws Exception {
        if (!this.initialized) {
            initialize(context);
        }

        Record<?> record = context.getCurrentRecord();

        if (isEventTime()) {
            long ts = this.timestampExtractor.extractTimestamp(input);
            if (this.waterMarkEventGenerator.track(record.getTopicName().get(), ts)) {
                this.windowManager.add(input, ts, record);
            } else {
                if (this.windowConfig.getLateDataTopic() != null) {
                    context.publish(this.windowConfig.getLateDataTopic(), input);
                } else {
                    log.info(String.format(
                            "Received a late tuple %s with ts %d. This will not be " + "processed"
                                    + ".", input, ts));
                }
                record.ack();
            }
        } else {
            this.windowManager.add(input, System.currentTimeMillis(), record);
        }
        return null;
    }

    public O process(Window<I> inputWindow, WindowContext context) throws Exception {
        return this.windowFunction.apply(inputWindow.get());
    }
}
