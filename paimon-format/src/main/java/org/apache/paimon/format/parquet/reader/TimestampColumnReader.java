/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.format.parquet.reader;

import org.apache.paimon.data.Timestamp;
import org.apache.paimon.data.columnar.writable.WritableIntVector;
import org.apache.paimon.data.columnar.writable.WritableTimestampVector;

import org.apache.parquet.column.ColumnDescriptor;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.schema.PrimitiveType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import static org.apache.paimon.utils.Preconditions.checkArgument;

/**
 * Timestamp {@link ColumnReader}. We only support INT96 bytes now, julianDay(4) + nanosOfDay(8).
 * See <a
 * href="https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#timestamp">Parquet
 * Timestamp</a> TIMESTAMP_MILLIS and TIMESTAMP_MICROS are the deprecated ConvertedType.
 */
public class TimestampColumnReader extends AbstractColumnReader<WritableTimestampVector> {

    public static final int JULIAN_EPOCH_OFFSET_DAYS = 2_440_588;
    public static final long MILLIS_IN_DAY = TimeUnit.DAYS.toMillis(1);
    public static final long NANOS_PER_MILLISECOND = TimeUnit.MILLISECONDS.toNanos(1);
    public static final long NANOS_PER_SECOND = TimeUnit.SECONDS.toNanos(1);

    private final boolean utcTimestamp;

    public TimestampColumnReader(
            boolean utcTimestamp, ColumnDescriptor descriptor, PageReadStore pageReadStore)
            throws IOException {
        super(descriptor, pageReadStore);
        this.utcTimestamp = utcTimestamp;
        checkTypeName(PrimitiveType.PrimitiveTypeName.INT96);
    }

    @Override
    protected boolean supportLazyDecode() {
        return utcTimestamp;
    }

    @Override
    protected void readBatch(int rowId, int num, WritableTimestampVector column) {
        for (int i = 0; i < num; i++) {
            if (runLenDecoder.readInteger() == maxDefLevel) {
                ByteBuffer buffer = readDataBuffer(12);
                column.setTimestamp(
                        rowId + i,
                        int96ToTimestamp(utcTimestamp, buffer.getLong(), buffer.getInt()));
            } else {
                column.setNullAt(rowId + i);
            }
        }
    }

    @Override
    protected void skipBatch(int num) {
        for (int i = 0; i < num; i++) {
            if (runLenDecoder.readInteger() == maxDefLevel) {
                skipDataBuffer(12);
            }
        }
    }

    @Override
    protected void readBatchFromDictionaryIds(
            int rowId, int num, WritableTimestampVector column, WritableIntVector dictionaryIds) {
        for (int i = rowId; i < rowId + num; ++i) {
            if (!column.isNullAt(i)) {
                column.setTimestamp(
                        i,
                        decodeInt96ToTimestamp(utcTimestamp, dictionary, dictionaryIds.getInt(i)));
            }
        }
    }

    public static Timestamp decodeInt96ToTimestamp(
            boolean utcTimestamp, org.apache.parquet.column.Dictionary dictionary, int id) {
        Binary binary = dictionary.decodeToBinary(id);
        checkArgument(binary.length() == 12, "Timestamp with int96 should be 12 bytes.");
        ByteBuffer buffer = binary.toByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
        return int96ToTimestamp(utcTimestamp, buffer.getLong(), buffer.getInt());
    }

    public static Timestamp int96ToTimestamp(boolean utcTimestamp, long nanosOfDay, int julianDay) {
        long millisecond = julianDayToMillis(julianDay) + (nanosOfDay / NANOS_PER_MILLISECOND);

        if (utcTimestamp) {
            int nanoOfMillisecond = (int) (nanosOfDay % NANOS_PER_MILLISECOND);
            return Timestamp.fromEpochMillis(millisecond, nanoOfMillisecond);
        } else {
            java.sql.Timestamp timestamp = new java.sql.Timestamp(millisecond);
            timestamp.setNanos((int) (nanosOfDay % NANOS_PER_SECOND));
            return Timestamp.fromSQLTimestamp(timestamp);
        }
    }

    private static long julianDayToMillis(int julianDay) {
        return (julianDay - JULIAN_EPOCH_OFFSET_DAYS) * MILLIS_IN_DAY;
    }
}
