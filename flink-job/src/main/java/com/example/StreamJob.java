package com.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.*;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

/**
 * Real-time click stream processing job
 * 
 * This job:
 * 1. Reads click events from Kafka
 * 2. Assigns event time and watermarks
 * 3. Performs windowed aggregations (1-minute tumbling windows)
 * 4. Calculates counts and unique users per page/country
 * 5. Writes results to ClickHouse
 */
public class StreamJob {

    // Data classes for type safety
    public static class Click {
        public String event_id;
        public String user_id;
        public long ts;
        public String page;
        public String referrer;
        public String country;
        public String device;

        public Click() {}

        @Override
        public String toString() {
            return String.format("Click{event_id='%s', user_id='%s', ts=%d, page='%s', country='%s', device='%s'}", 
                    event_id, user_id, ts, page, country, device);
        }
    }

    public static class PageMinuteAgg {
        public Timestamp window_start;
        public Timestamp window_end;
        public String page;
        public String country;
        public long cnt;
        public long unique_users;

        public PageMinuteAgg() {}

        public PageMinuteAgg(Timestamp window_start, Timestamp window_end, String page, String country, long cnt, long unique_users) {
            this.window_start = window_start;
            this.window_end = window_end;
            this.page = page;
            this.country = country;
            this.cnt = cnt;
            this.unique_users = unique_users;
        }

        @Override
        public String toString() {
            return String.format("PageMinuteAgg{window_start=%s, page='%s', country='%s', cnt=%d, unique_users=%d}", 
                    window_start, page, country, cnt, unique_users);
        }
    }

    /**
     * Window function to aggregate clicks per page and country
     */
    public static class WindowAggregateFunction extends ProcessWindowFunction<Click, PageMinuteAgg, Tuple2<String, String>, TimeWindow> {
        
        @Override
        public void process(Tuple2<String, String> key, Context context, Iterable<Click> elements, Collector<PageMinuteAgg> out) {
            String page = key.f0;
            String country = key.f1;
            
            Set<String> uniqueUsers = new HashSet<>();
            long count = 0;
            
            // Process all clicks in this window
            for (Click click : elements) {
                count++;
                uniqueUsers.add(click.user_id);
            }
            
            // Create window timestamps
            Timestamp windowStart = new Timestamp(context.window().getStart());
            Timestamp windowEnd = new Timestamp(context.window().getEnd());
            
            // Emit aggregated result
            PageMinuteAgg result = new PageMinuteAgg(windowStart, windowEnd, page, country, count, uniqueUsers.size());
            out.collect(result);
            
            // Log progress for monitoring
            if (count > 0) {
                System.out.println(String.format("Window [%s - %s]: page=%s, country=%s, count=%d, unique_users=%d", 
                        windowStart, windowEnd, page, country, count, uniqueUsers.size()));
            }
        }
    }

    /**
     * Anomaly detection function (optional enhancement)
     */
    public static class AnomalyDetectionFunction extends ProcessWindowFunction<PageMinuteAgg, Tuple5<PageMinuteAgg, Boolean, Double, Double, String>, Tuple2<String, String>, TimeWindow> {
        
        private transient ValueState<Double> meanState;
        private transient ValueState<Double> varianceState;
        private transient ValueState<Long> countState;
        
        @Override
        public void open(Configuration parameters) {
            meanState = getRuntimeContext().getState(new ValueStateDescriptor<>("mean", Types.DOUBLE));
            varianceState = getRuntimeContext().getState(new ValueStateDescriptor<>("variance", Types.DOUBLE));
            countState = getRuntimeContext().getState(new ValueStateDescriptor<>("count", Types.LONG));
        }
        
        @Override
        public void process(Tuple2<String, String> key, Context context, Iterable<PageMinuteAgg> elements, 
                          Collector<Tuple5<PageMinuteAgg, Boolean, Double, Double, String>> out) throws Exception {
            
            PageMinuteAgg agg = elements.iterator().next(); // Should only have one element
            
            // Get current statistics
            Double currentMean = meanState.value();
            Double currentVariance = varianceState.value();
            Long currentCount = countState.value();
            
            if (currentMean == null) {
                currentMean = 0.0;
                currentVariance = 0.0;
                currentCount = 0L;
            }
            
            // Update rolling statistics using Welford's algorithm
            currentCount++;
            double delta = agg.cnt - currentMean;
            currentMean += delta / currentCount;
            double delta2 = agg.cnt - currentMean;
            currentVariance += delta * delta2;
            
            // Update state
            meanState.update(currentMean);
            varianceState.update(currentVariance);
            countState.update(currentCount);
            
            // Calculate z-score for anomaly detection
            boolean isAnomaly = false;
            double zScore = 0.0;
            double threshold = 2.5; // 2.5 standard deviations
            String anomalyReason = "";
            
            if (currentCount > 5) { // Need at least 5 data points
                double stdDev = Math.sqrt(currentVariance / (currentCount - 1));
                if (stdDev > 0) {
                    zScore = Math.abs(agg.cnt - currentMean) / stdDev;
                    if (zScore > threshold) {
                        isAnomaly = true;
                        anomalyReason = String.format("Z-score %.2f exceeds threshold %.2f", zScore, threshold);
                    }
                }
            }
            
            out.collect(new Tuple5<>(agg, isAnomaly, zScore, currentMean, anomalyReason));
        }
    }

    public static void main(String[] args) throws Exception {
        
        // Set up the execution environment
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // Configure checkpointing for fault tolerance
        env.enableCheckpointing(30000); // Checkpoint every 30 seconds
        
        // Configure parallelism
        env.setParallelism(2);
        
        // Create Kafka source
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers("kafka:9092")
                .setTopics("click_events")
                .setGroupId("flink-click-processor")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        // Create data stream from Kafka
        DataStream<String> kafkaStream = env.fromSource(source, 
                WatermarkStrategy.<String>forBoundedOutOfOrderness(Duration.ofSeconds(10))
                        .withTimestampAssigner((event, timestamp) -> {
                            try {
                                ObjectMapper mapper = new ObjectMapper();
                                Click click = mapper.readValue(event, Click.class);
                                return click.ts;
                            } catch (Exception e) {
                                System.err.println("Failed to parse timestamp from: " + event + ", error: " + e.getMessage());
                                return System.currentTimeMillis();
                            }
                        }), "kafka-source");

        // Parse JSON and convert to Click objects
        ObjectMapper mapper = new ObjectMapper();
        DataStream<Click> clickStream = kafkaStream
                .map(new MapFunction<String, Click>() {
                    @Override
                    public Click map(String value) throws Exception {
                        try {
                            return mapper.readValue(value, Click.class);
                        } catch (Exception e) {
                            System.err.println("Failed to parse JSON: " + value + ", error: " + e.getMessage());
                            // Return a dummy click to avoid breaking the stream
                            Click dummy = new Click();
                            dummy.event_id = "parse-error";
                            dummy.user_id = "unknown";
                            dummy.ts = System.currentTimeMillis();
                            dummy.page = "/error";
                            dummy.country = "UNKNOWN";
                            dummy.device = "unknown";
                            return dummy;
                        }
                    }
                })
                .filter(click -> !"parse-error".equals(click.event_id)); // Filter out parse errors

        // Aggregate clicks by page and country in 1-minute tumbling windows
        DataStream<PageMinuteAgg> aggregatedStream = clickStream
                .keyBy(new KeySelector<Click, Tuple2<String, String>>() {
                    @Override
                    public Tuple2<String, String> getKey(Click click) throws Exception {
                        return new Tuple2<>(click.page, click.country);
                    }
                })
                .window(TumblingEventTimeWindows.of(Time.minutes(1)))
                .process(new WindowAggregateFunction());

        // Optional: Add anomaly detection
        DataStream<Tuple5<PageMinuteAgg, Boolean, Double, Double, String>> anomalyStream = aggregatedStream
                .keyBy(new KeySelector<PageMinuteAgg, Tuple2<String, String>>() {
                    @Override
                    public Tuple2<String, String> getKey(PageMinuteAgg agg) throws Exception {
                        return new Tuple2<>(agg.page, agg.country);
                    }
                })
                .window(TumblingEventTimeWindows.of(Time.minutes(1)))
                .process(new AnomalyDetectionFunction());

        // Print anomalies for monitoring
        anomalyStream
                .filter(tuple -> tuple.f1) // Only anomalies
                .map(tuple -> String.format("ANOMALY DETECTED: %s - %s", tuple.f0, tuple.f4))
                .print("Anomalies");

        // Extract the aggregated data for sinking
        DataStream<PageMinuteAgg> finalAggStream = anomalyStream.map(tuple -> tuple.f0);

        // Sink to ClickHouse
        finalAggStream.addSink(JdbcSink.sink(
                "INSERT INTO rt.page_minute_agg (window_start, window_end, page, country, cnt, unique_users) VALUES (?, ?, ?, ?, ?, ?)",
                (statement, agg) -> {
                    statement.setTimestamp(1, agg.window_start);
                    statement.setTimestamp(2, agg.window_end);
                    statement.setString(3, agg.page);
                    statement.setString(4, agg.country);
                    statement.setLong(5, agg.cnt);
                    statement.setLong(6, agg.unique_users);
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(100)
                        .withBatchIntervalMs(5000)
                        .withMaxRetries(3)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl("jdbc:clickhouse://clickhouse:8123/rt")
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername("default")
                        .withPassword("")
                        .build()
        )).name("ClickHouse Sink");

        // Also sink raw data to ClickHouse for debugging and analysis
        clickStream.addSink(JdbcSink.sink(
                "INSERT INTO rt.clicks_raw (event_id, user_id, ts, page, referrer, country, device) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (statement, click) -> {
                    statement.setString(1, click.event_id);
                    statement.setString(2, click.user_id);
                    statement.setTimestamp(3, new Timestamp(click.ts));
                    statement.setString(4, click.page);
                    statement.setString(5, click.referrer);
                    statement.setString(6, click.country);
                    statement.setString(7, click.device);
                },
                JdbcExecutionOptions.builder()
                        .withBatchSize(1000)
                        .withBatchIntervalMs(10000)
                        .withMaxRetries(3)
                        .build(),
                new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
                        .withUrl("jdbc:clickhouse://clickhouse:8123/rt")
                        .withDriverName("com.clickhouse.jdbc.ClickHouseDriver")
                        .withUsername("default")
                        .withPassword("")
                        .build()
        )).name("ClickHouse Raw Sink");

        // Execute the job
        env.execute("Real-time Click Stream Processing");
    }
}