/*
 * Copyright 1999-2021 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.metric.prom.collector;

import com.alibaba.csp.sentinel.config.SentinelConfig;
import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.metric.prom.MetricTypeConstants;
import com.alibaba.csp.sentinel.metric.prom.config.PrometheusGlobalConfig;
import com.alibaba.csp.sentinel.metric.prom.types.GaugeMetricFamily;
import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.node.metric.MetricSearcher;
import com.alibaba.csp.sentinel.node.metric.MetricWriter;
import com.alibaba.csp.sentinel.util.PidUtil;
import io.prometheus.client.Collector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The{@link PromExporterInit} Collector for prometheus exporter.
 *
 * @author karl-sy
 * @date 2023-07-13 21:15
 * @since 2.0.0
 */
public class SentinelCollector extends Collector {

    private final Object lock = new Object();

    private static final int ONE_SECOND = 1000;
    private static final String appName = PrometheusGlobalConfig.getPromFetchApp();

    private static final String[] types =  PrometheusGlobalConfig.getPromFetchTypes();

    private static final String identify =  PrometheusGlobalConfig.getPromFetchIdentify();

    private static final int fetchSize = PrometheusGlobalConfig.getPromFetchSize();

    private static final int delayTime = PrometheusGlobalConfig.getPromFetchDelayTime();

    private volatile MetricSearcher searcher;

    private volatile Long lastFetchTime;

    @Override
    public List<MetricFamilySamples> collect() {
        if (searcher == null) {
            synchronized (lock) {
                if (searcher == null) {
                    searcher = new MetricSearcher(MetricWriter.METRIC_BASE_DIR,
                            MetricWriter.formMetricFileName(SentinelConfig.getAppName(), PidUtil.getPid()));
                }
                RecordLog.warn("[SentinelCollector] init sentinel metrics searcher with appName:{}", appName);
                lastFetchTime = System.currentTimeMillis() / ONE_SECOND * ONE_SECOND;
            }
        }

        List<MetricFamilySamples> list = new ArrayList<>();

        long endTime = System.currentTimeMillis() / ONE_SECOND * ONE_SECOND - (long) delayTime * ONE_SECOND;
        try {
            List<MetricNode> nodes = searcher.findByTimeAndResource(lastFetchTime, endTime, identify);
            if(nodes == null){
                return list;
            }
            if(nodes.size() > fetchSize){
                nodes = nodes.subList(0,fetchSize);
            }
            GaugeMetricFamily metricFamily = new GaugeMetricFamily(appName,
                    "sentinel_metrics", Arrays.asList("resource","classification","type"));
            for (MetricNode node : nodes) {
                long recordTime = node.getTimestamp();
                for (String type : types) {
                    double val = getTypeVal(node,type);
                    metricFamily.addMetric(Arrays.asList(node.getResource(), String.valueOf(node.getClassification()),type), val,recordTime);
                }
            }
            list.add(metricFamily);
        } catch (Exception e) {
            RecordLog.warn("[SentinelCollector] failed to fetch sentinel metrics with exception:", e);
        }finally {
            lastFetchTime = endTime + ONE_SECOND;
        }

        return list;
    }

    public double getTypeVal(MetricNode node,String type){
        if(MetricTypeConstants.PASS_QPS.equals(type)){
            return node.getPassQps();
        }
        if(MetricTypeConstants.BLOCK_QPS.equals(type)){
            return node.getBlockQps();
        }
        if(MetricTypeConstants.SUCCESS_QPS.equals(type)){
            return node.getSuccessQps();
        }
        if(MetricTypeConstants.EXCEPTION_QPS.equals(type)){
            return node.getExceptionQps();
        }
        if(MetricTypeConstants.RT.equals(type)){
            return node.getRt();
        }
        if(MetricTypeConstants.OCC_PASS_QPS.equals(type)){
            return node.getOccupiedPassQps();
        }
        if(MetricTypeConstants.CONCURRENCY.equals(type)){
            return node.getConcurrency();
        }
        return -1.0;
    }
}
