package org.rakam.presto.analysis;

import com.facebook.presto.jdbc.internal.client.ClientSession;
import com.facebook.presto.jdbc.internal.client.ClientTypeSignatureParameter;
import com.facebook.presto.jdbc.internal.client.ErrorLocation;
import com.facebook.presto.jdbc.internal.client.QueryResults;
import com.facebook.presto.jdbc.internal.client.StatementClient;
import com.facebook.presto.jdbc.internal.client.StatementStats;
import com.facebook.presto.jdbc.internal.guava.collect.Lists;
import com.facebook.presto.jdbc.internal.guava.util.concurrent.ThreadFactoryBuilder;
import com.facebook.presto.jdbc.internal.spi.type.StandardTypes;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import org.rakam.collection.FieldType;
import org.rakam.collection.SchemaField;
import org.rakam.report.QueryError;
import org.rakam.report.QueryExecution;
import org.rakam.report.QueryResult;
import org.rakam.report.QueryStats;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.facebook.presto.jdbc.internal.spi.type.ParameterKind.TYPE;
import static java.time.ZoneOffset.UTC;
import static org.rakam.collection.FieldType.*;

public class PrestoQueryExecution implements QueryExecution {
    private final static Logger LOGGER = Logger.get(PrestoQueryExecution.class);

    // doesn't seem to be a good way but presto client uses a synchronous http client
    // so it blocks the thread when executing queries
    private static final ExecutorService QUERY_EXECUTOR = new ThreadPoolExecutor(0, 50, 120L, TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadFactoryBuilder()
            .setNameFormat("presto-query-executor")
            .setUncaughtExceptionHandler((t, e) -> e.printStackTrace()).build());
    private static AtomicReference<ClientSession> defaultSession;
    private final List<List<Object>> data = Lists.newArrayList();
    private final TransactionHook transactionHook;
    private List<SchemaField> columns;

    private final CompletableFuture<QueryResult> result = new CompletableFuture<>();
    public static final DateTimeFormatter PRESTO_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d H:m:s.SSS");
    public static final DateTimeFormatter PRESTO_TIMESTAMP_WITH_TIMEZONE_FORMAT = DateTimeFormatter.ofPattern("yyyy-M-d H:m:s.SSS z");

    private final StatementClient client;
    private final Instant startTime;

    public PrestoQueryExecution(StatementClient client, TransactionHook transactionIdConsumer) {
        this.client = client;
        this.startTime = Instant.now();
        this.transactionHook = transactionIdConsumer;

        QUERY_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                while (client.isValid() && client.advance()) {
                    transformAndAdd(client.current());
                }

                if (client.isFailed()) {
                    com.facebook.presto.jdbc.internal.client.QueryError error = client.finalResults().getError();
                    ErrorLocation errorLocation = error.getErrorLocation();
                    QueryError queryError = new QueryError(error.getFailureInfo().getMessage(),
                            error.getSqlState(),
                            error.getErrorCode(),
                            errorLocation != null ? errorLocation.getLineNumber() : null,
                            errorLocation != null ? errorLocation.getColumnNumber() : null);
                    result.complete(QueryResult.errorResult(queryError));
                } else {
                    if(client.isClearTransactionId()) {
                        transactionHook.onClear();
                    }
                    transactionHook.setTransaction(client.getStartedtransactionId());

                    transformAndAdd(client.finalResults());

                    ImmutableMap<String, Object> stats = ImmutableMap.of(
                            QueryResult.EXECUTION_TIME, startTime.until(Instant.now(), ChronoUnit.MILLIS));

                    result.complete(new QueryResult(columns, data, stats));
                }
            }

            private void transformAndAdd(QueryResults result) {
                if(result.getError() != null || result.getColumns() == null) {
                    return;
                }

                if(columns == null) {
                    columns = result.getColumns().stream()
                            .map(c -> {
                                List<ClientTypeSignatureParameter> arguments = c.getTypeSignature().getArguments();
                                return new SchemaField(c.getName(), fromPrestoType(c.getTypeSignature().getRawType(),
                                        arguments.stream()
                                                .filter(argument -> argument.getKind() == TYPE)
                                                .map(argument -> argument.getTypeSignature().getRawType()).iterator()));
                            })
                            .collect(Collectors.toList());
                }

                if(result.getData() == null) {
                    return;
                }

                for (List<Object> objects : result.getData()) {
                    Object[] row = new Object[columns.size()];

                    for (int i = 0; i < objects.size(); i++) {
                        String type = result.getColumns().get(i).getTypeSignature().getRawType();
                        Object value = objects.get(i);
                        if(value != null) {
                            if(type.equals(StandardTypes.TIMESTAMP)) {
                                try {
                                    row[i] = LocalDateTime.parse((CharSequence) value, PRESTO_TIMESTAMP_FORMAT).toInstant(UTC);
                                } catch (Exception e) {
                                    LOGGER.error(e, "Error while parsing Presto TIMESTAMP.");
                                }
                            } else
                            if(type.equals(StandardTypes.TIMESTAMP_WITH_TIME_ZONE)) {
                                try {
                                    row[i] = LocalDateTime.parse((CharSequence) value, PRESTO_TIMESTAMP_WITH_TIMEZONE_FORMAT).toInstant(UTC);
                                } catch (Exception e) {
                                    LOGGER.error(e, "Error while parsing Presto TIMESTAMP WITH TIMEZONE.");
                                }
                            } else
                            if(type.equals(StandardTypes.DATE)){
                                row[i] = LocalDate.parse((CharSequence) value);
                            } else {
                                row[i] = objects.get(i);
                            }
                        } else {
                            row[i] = objects.get(i);
                        }
                    }

                    data.add(Arrays.asList(row));
                }
            }
        });
    }

    public static FieldType fromPrestoType(String rawType, Iterator<String> parameter) {
        switch (rawType) {
            case StandardTypes.BIGINT:
                return LONG;
            case StandardTypes.BOOLEAN:
                return BOOLEAN;
            case StandardTypes.DATE:
                return DATE;
            case StandardTypes.DOUBLE:
                return DOUBLE;
            case StandardTypes.VARBINARY:
            case StandardTypes.HYPER_LOG_LOG:
                return BINARY;
            case StandardTypes.VARCHAR:
                return STRING;
            case StandardTypes.TIME:
            case StandardTypes.TIME_WITH_TIME_ZONE:
                return TIME;
            case StandardTypes.TIMESTAMP:
            case StandardTypes.TIMESTAMP_WITH_TIME_ZONE:
                return TIMESTAMP;
            case StandardTypes.ARRAY:
                return fromPrestoType(parameter.next(), null).convertToArrayType();
            case StandardTypes.MAP:
                Preconditions.checkArgument(parameter.next().equals(StandardTypes.VARCHAR),
                        "The first parameter of MAP must be STRING");
                return fromPrestoType(parameter.next(), null).convertToMapValueType();
            default:
                return BINARY;
        }
    }

    @Override
    public QueryStats currentStats() {
        StatementStats stats = client.current().getStats();
        int totalSplits = stats.getTotalSplits();
        int percentage = totalSplits == 0 ? 0 : stats.getCompletedSplits() * 100 / totalSplits;
        return new QueryStats(percentage,
                QueryStats.State.valueOf(stats.getState().toUpperCase(Locale.ENGLISH)),
                stats.getNodes(),
                stats.getProcessedRows(),
                stats.getProcessedBytes(),
                stats.getUserTimeMillis(),
                stats.getCpuTimeMillis(),
                stats.getWallTimeMillis());
    }

    @Override
    public boolean isFinished() {
        return result.isDone();
    }

    @Override
    public CompletableFuture<QueryResult> getResult() {
        return result;
    }

    public String getQuery() {
        return client.getQuery();
    }

    @Override
    public void kill() {
        client.close();
    }

    public interface TransactionHook {
        void onClear();
        void setTransaction(String transactionId);
    }
}
