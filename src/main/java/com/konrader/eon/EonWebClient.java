package com.konrader.eon;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class EonWebClient {
	private final URI apiUri = URI.create("https://minasidor.eon.se/eon-online/");
	private final HttpClient client;
	private final Path cachePath;
	private final boolean fromCache;
	private Date contractStart = null;

	public EonWebClient() {
		this(null, false);
	}

	public EonWebClient(Path cachePath, boolean fromCache) {
		if (fromCache)
			client = null;
		else
			client = HttpClient.newBuilder().followRedirects(Redirect.NORMAL)
					.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
		this.cachePath = cachePath;
		this.fromCache = fromCache;
	}

	public void login(String userid, String password) throws IOException {
		if (fromCache)
			return;

		HttpRequest request = HttpRequest.newBuilder(apiUri.resolve("loginservlet"))
				.header("Content-Type", "application/json; charset=UTF-8")
				.POST(BodyPublishers.ofString("userIdType=4&userId=" + userid + "&password=" + password + "&cookies=1"))
				.build();

		try {
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
			JSONObject json = new JSONObject(response.body());
			int status = Integer.parseInt(json.getString("Status"));
			if (status != 1)
				throw new RuntimeException("Failed login to EON as '" + userid + "', status: " + status);

			JSONArray arr = json.getJSONObject("Customer").getJSONArray("Accounts");
			for (int i = 0; i < arr.length(); i++) {
				JSONObject jacc = arr.getJSONObject(i);
				if (jacc.getString("Ytype").equals("NÄT"))
					parseStartDate(jacc.getString("EinZDat"));
			}

		} catch (IOException e) {
			throw e;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	public Date getContractStart() {
		return contractStart;
	}

	private void parseStartDate(String dateStr) {
		SimpleDateFormat df = new SimpleDateFormat("dd-MM-yyyy");
		try {
			contractStart = df.parse(dateStr);
		} catch (ParseException e) {
			e.printStackTrace();
		}
	}

	public List<List<Integer>> getConsumptionMonth(String installation, int year, int month) throws IOException {
		String formData = "radioChosen=KWH" + "&ar=a" + "&installationSelector="
				+ URLEncoder.encode(installation, Charset.forName("UTF-8")) + "&year=" + year + "&month=" + month
				+ "&type=P";
		String html = postForm(apiUri.resolve("eon.consumption.month.sap"), formData, "month_" + year + "_" + month);
		Document doc = Jsoup.parse(html);
		Element table = doc.select("table").get(2);
		int rowNum = 0;
		List<List<Integer>> ret = new ArrayList<>();
		for (Element row : table.select("tr")) {
			if (rowNum > 4) {
				Elements cols = row.select("td");
				if (cols.get(1).childNodeSize() < 3)
					break;
				// int day =
				// Integer.parseInt(cols.get(1).childNodes().get(1).childNode(0).toString());
				List<Integer> values = new ArrayList<>();
				for (int h = 0; h < 24; h++) {
					String str = cols.get(h + 3).text();
					if (str.equals("-"))
						values.add(null);
					else
						values.add((int) (Float.parseFloat(str) * 1000));
				}
				if (values.isEmpty())
					ret.add(null);
				else
					ret.add(values);

			}
			rowNum++;
		}
		return ret;
	}

	private String postForm(URI uri, String body, String cacheName) throws IOException {
		if (fromCache)
			return Files.readString(cachePath.resolve(cacheName));

		HttpRequest request = HttpRequest.newBuilder(uri).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(BodyPublishers.ofString(body)).build();

		try {
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
			if (cachePath != null)
				Files.writeString(cachePath.resolve(cacheName), response.body());
			return response.body();
		} catch (IOException e) {
			throw e;
		} catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

}
