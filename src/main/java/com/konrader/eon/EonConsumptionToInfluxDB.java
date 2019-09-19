package com.konrader.eon;

import java.io.IOException;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.influxdb.InfluxDB;
import org.influxdb.InfluxDBFactory;
import org.influxdb.dto.BatchPoints;
import org.influxdb.dto.Point;
import org.influxdb.dto.Query;
import org.influxdb.dto.QueryResult;
import org.influxdb.dto.QueryResult.Result;

public class EonConsumptionToInfluxDB {
	private static final String influxMeasurement = "consumed";
	private final EonWebClient eon;
	private final InfluxDB influx;
	private final String eonInstallation;
	private Map<String, SortedMap<Long, Integer>> eonCache = new HashMap<>();

	EonConsumptionToInfluxDB(String eonUsername, String eonPassword, String eonInstallation, String influxHost,
			String influxDbName, String influxUsername, String influxPassword) throws IOException {
		eon = new EonWebClient();
		eon.login(eonUsername, eonPassword);
		this.eonInstallation = eonInstallation;
		influx = InfluxDBFactory.connect("http://" + influxHost + ":8086", influxUsername, influxPassword);
		checkInfluxDb(influxDbName);
		influx.setDatabase(influxDbName);

		long lastInfluxTime = lastInfluxTime(influxDbName);
		// System.out.println("lastInfluxTime: " + new Date(lastInfluxTime));
		long lastEonTime = lastEonTime();
		// System.out.println("lastEonTime: " + new Date(lastEonTime));

		if (lastEonTime > lastInfluxTime) {
			if (lastInfluxTime < 0) {
				lastInfluxTime = eon.getContractStart().getTime();
				System.out.println(
						"No prior data in InfluxDB, fetching from EON contract start: " + new Date(lastInfluxTime));
			}
			Calendar cal = new Calendar.Builder().setTimeZone(TimeZone.getDefault()).setInstant(lastInfluxTime).build();
			NavigableMap<Long, Integer> data = new TreeMap<>();
			data.putAll(eonConsumption(cal));
			while (data.lastKey() < lastEonTime) {
				cal.add(Calendar.MONTH, 1);
				data.putAll(eonConsumption(cal));
			}

			NavigableMap<Long, Integer> newData = data.tailMap(lastInfluxTime, false);
			System.out.println(newData.size() + " new data points (lastEonTime: " + new Date(lastEonTime) + ")");
			influxWrite(influxDbName, newData.entrySet());
		} else {
			System.out.println("No new data (lastEonTime: " + new Date(lastEonTime) + ")");
		}
		influx.close();
	}

	void checkInfluxDb(String influxDbName) {
		QueryResult qres = influx.query(new Query("SHOW DATABASES"));
		for (List<Object> vals : qres.getResults().get(0).getSeries().get(0).getValues()) {
			if (vals.get(0).toString().equals(influxDbName))
				return;
		}
		System.out.println("InfluxDB has no database '" + influxDbName + "', creating it");
		qres = influx.query(new Query("CREATE DATABASE " + influxDbName));
		if (qres.hasError())
			throw new RuntimeException("Failed creating InfluxDB database '" + influxDbName + "' > " + qres.getError());
	}

	long lastInfluxTime(String influxDbName) {
		QueryResult qres = influx.query(new Query("SELECT last(*) FROM " + influxMeasurement, influxDbName),
				TimeUnit.MILLISECONDS);
		if (qres.hasError())
			throw new RuntimeException("Failed to query for last value: " + qres.getError());

		Result res = qres.getResults().get(0);
		if (res.hasError())
			throw new RuntimeException("Failed to query for last value: " + res.getError());

		// Empty database
		if (res.getSeries() == null)
			return -1;

		return ((Double) res.getSeries().get(0).getValues().get(0).get(0)).longValue();

	}

	void influxWrite(String influxDbName, Collection<Entry<Long, Integer>> entries) {
		Iterator<Entry<Long, Integer>> iter = entries.iterator();
		while (iter.hasNext()) {
			long t1 = System.currentTimeMillis();
			BatchPoints.Builder bpb = BatchPoints.database(influxDbName);
			for (int i = 0; i < 100 && iter.hasNext(); i++) {
				Entry<Long, Integer> entry = iter.next();
				bpb.point(Point.measurement(influxMeasurement).time(entry.getKey(), TimeUnit.MILLISECONDS)
						.addField("value", entry.getValue()).build());
			}
			BatchPoints bp = bpb.build();
			influx.write(bp);
			long t2 = System.currentTimeMillis();
			System.out.println("Wrote " + bp.getPoints().size() + " points to InfluxDB in " + (t2 - t1) + "ms");
		}
	}

	long lastEonTime() throws IOException {
		Calendar cal = Calendar.getInstance();
		var data = eonConsumption(cal);
		if (data.isEmpty()) {
			// Current month is empty, try month before
			cal.add(Calendar.MONTH, -1);
			data = eonConsumption(cal);
		}
		return data.lastKey();
	}

	SortedMap<Long, Integer> eonConsumption(Calendar cal) throws IOException {
		return eonConsumption(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1);
	}

	SortedMap<Long, Integer> eonConsumption(int year, int month) throws IOException {
		String cacheKey = year + "-" + month;
		SortedMap<Long, Integer> data = eonCache.get(cacheKey);
		if (data == null) {
			data = new TreeMap<>();
			var days = eon.getConsumptionMonth(eonInstallation, year, month);
			// System.out.println("Live fetch EON " + year + "-" + month + " returned data
			// for " + days.size() + " days");
			int day = 1;
			for (List<Integer> dayData : days) {
				if (dayData != null) {
					int hour = 1;
					for (Integer hourData : dayData) {
						if (hourData != null) {
							Calendar cal = new Calendar.Builder().setTimeZone(TimeZone.getDefault())
									.setDate(year, month - 1, day).setTimeOfDay(hour, 0, 0, 0).build();
							cal.add(Calendar.MINUTE, -30);
							data.put(cal.getTimeInMillis(), hourData);
						}
						hour++;
					}
				}
				day++;
			}
			eonCache.put(cacheKey, data);
		}
		return data;
	}

	public static void main(String[] args) throws IOException {
		if (args.length < 7)
			throw new IllegalArgumentException(
					"Requires cmd-line params: eonUsername eonPassword eonInstallation influxHost influxDbName influxUsername influxPassword");

		new EonConsumptionToInfluxDB(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
	}

}
