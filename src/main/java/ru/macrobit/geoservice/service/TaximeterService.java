package ru.macrobit.geoservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.GHPoint3D;
import org.apache.http.HttpEntity;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.macrobit.drivertaxi.taximeter.TaximeterLocation;
import ru.macrobit.drivertaxi.taximeter.api.TaximeterAPI;
import ru.macrobit.drivertaxi.taximeter.api.TaximeterAPIImpl;
import ru.macrobit.drivertaxi.taximeter.api.TaximeterAPIResult;
import ru.macrobit.drivertaxi.taximeter.api.TaximeterRequest;
import ru.macrobit.drivertaxi.taximeter.ordersdata.TaximeterInterval;
import ru.macrobit.drivertaxi.taximeter.taximeterParams.TaximeterParams;
import ru.macrobit.drivertaxi.taximeter.taximeterParams.TaximeterParamsImpl;
import ru.macrobit.drivertaxi.taximeter.taximeterParams.polygons.PolygonWithDataImpl;
import ru.macrobit.drivertaxi.taximeter.taximeterParams.polygons.PolygonsImpl;
import ru.macrobit.drivertaxi.taximeter.taximeterParams.polygons.polygon.Point;
import ru.macrobit.geoservice.TaximeterLogDAO;
import ru.macrobit.geoservice.common.GraphUtils;
import ru.macrobit.geoservice.common.PropertiesFileReader;
import ru.macrobit.geoservice.pojo.Area;
import ru.macrobit.geoservice.pojo.BatchRequest;
import ru.macrobit.geoservice.pojo.LogEntry;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Created by [david] on 05.10.16.
 */
@Singleton
@Startup
public class TaximeterService {
    private static final Logger logger = LoggerFactory.getLogger(TaximeterService.class);
    private TypeReference<List<Area>> typeReference = new TypeReference<List<Area>>() {
    };
    private PolygonsImpl polygons = new PolygonsImpl();
    private volatile boolean ready = false;
    private Thread areaFetcher;
    private GraphHopper hopper;
    private static int POOL_SIZE = 300;
    private ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, new ThreadFactory() {
        private AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r);
            thread.setName("RoutingServiceThreadPool " + counter.addAndGet(1));
            return thread;
        }
    });

    @Inject
    private TaximeterLogDAO taximeterLogDAO;

    @PostConstruct
    public void init() {
        hopper = new GraphHopper().forServer();
        hopper.setOSMFile(PropertiesFileReader.getOsmFilePath());
        hopper.setGraphHopperLocation(PropertiesFileReader.getSecondGraphFolder());
        hopper.setEncodingManager(new EncodingManager("car"));
        hopper.importOrLoad();
        areaFetcher = new Thread(() -> {
            while (!ready) {
                try {
                    reloadPolygons();
                    ready = true;
                } catch (Exception e) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e1) {
                        logger.error("{}", e1);
                    }
                    logger.error("{}", e);
                }
            }
        });
        areaFetcher.start();
        for (int i = 0; i < POOL_SIZE; i++) {
            pool.submit(() -> null);
        }
    }

    public void buildLogs(String orderId, Double maxDist, Long maxTimeout) {
        List<LogEntry> taximeterLogs;
        List<LogEntry> logs = taximeterLogDAO.getLogs(orderId, null, true);
        /*
        GraphHopperStorage graph = hopper.getGraphHopperStorage();
        LocationIndexMatch locationIndex = new LocationIndexMatch(graph,
                (LocationIndexTree) hopper.getLocationIndex());
        MapMatching mapMatching = new MapMatching(graph, locationIndex, encoder);
        List<GPXEntry> gpxEntries = logs.stream().map(log -> new GPXEntry(log.getLat(), log.getLon(), log.getTimestamp())).collect(Collectors.toList());
        MatchResult mr = mapMatching.doWork(gpxEntries);
        List<EdgeMatch> matches = mr.getEdgeMatches();
        logs = matches.stream().map(edge -> {
            LogEntry log = new LogEntry();
            log.setLat();
        }).collect(Collectors.toList());
*/
        taximeterLogs = new ArrayList<>(logs);
        int index = 1;
        for (int i = 0; i < logs.size() - 1; i++) {
            long timeout = logs.get(i + 1).getTimestamp() - logs.get(i).getTimestamp();
            if (timeout > maxTimeout) {
                PointList pointList = calcRoute(logs.get(i), logs.get(i + 1));
                Iterator<GHPoint3D> iterator = pointList.iterator();
                long timestamp = logs.get(i).getTimestamp();
                long incremet = (timeout / pointList.size());
                while (iterator.hasNext()) {
                    GHPoint3D point = iterator.next();
                    timestamp += incremet;
                    LogEntry logEntry = new LogEntry();
                    logEntry.setLat(point.getLat());
                    logEntry.setLon(point.getLon());
                    logEntry.setTimestamp(timestamp);
                    logEntry.setError("10");
                    logEntry.setSrc("gps");
                    logEntry.setBuilded(true);
                    taximeterLogs.add(index, logEntry);
                    index++;
                }
                index++;
            } else {
                index++;
            }
        }
        taximeterLogDAO.bulkInsert(taximeterLogs.stream().filter(logEntry -> logEntry.isBuilded()).collect(Collectors.toSet()), orderId);
    }

    public TaximeterAPIResult calculate(ru.macrobit.geoservice.pojo.TaximeterRequest taximeterRequest) throws Exception {
        TaximeterParams params = new TaximeterParamsImpl(taximeterRequest.getTarif());
        List<LogEntry> logs = taximeterLogDAO.getLogs(taximeterRequest.getOrderId(), taximeterRequest.getIndex(), taximeterRequest.isBuild());
        TaximeterRequest request = new TaximeterRequest.Builder(params)
                .setConstantInterval(20000)
                .setLocations(logs)
                .setPolygons(polygons)
                .setTaximeterLogger(new TaximeterLogger())
                .build();
        TaximeterAPI api = new TaximeterAPIImpl(request);
        return api.calculate();
    }

    public PointList calcRoute(LogEntry from, LogEntry to) {
        GHResponse rsp = hopper.route(GraphUtils.createRequest(from.getLat(), from.getLon(), to.getLat(), to.getLon()));
        if (rsp.hasErrors()) {
            logger.error("{}", rsp.getErrors());
            return null;
        }

        PathWrapper path = rsp.getBest();
        return path.getPoints();
    }

    public Object calcDistances(BatchRequest batchRequest) {
        int batchSize = batchRequest.getDests().values().size();
        if (batchSize == 0) {
            return null;
        }
        if (batchSize == 1) {
            Map<String, Double> resMap = new HashMap<>();
            Map.Entry<String, double[]> entry = batchRequest.getDests().entrySet().iterator().next();
            resMap.put(entry.getKey(), getDistance(batchRequest.getSrc(), entry.getValue()));
            return resMap;
        }

        Map<String, Future<Double>> futureMap = new HashMap<>();
        batchRequest.getDests().forEach((key, location) -> futureMap.put(key, pool.submit(() -> getDistance(location, batchRequest.getSrc()))));
        Map<String, Double> response = new HashMap<>();
        futureMap.forEach((key, val) -> {
            try {
                response.put(key, val.get(7, TimeUnit.MILLISECONDS));
            } catch (TimeoutException | InterruptedException | ExecutionException e) {
                response.put(key, GraphUtils.getDummyDist(batchRequest.getSrc(), batchRequest.getDests().get(key)));
            }
        });
        return response;
    }

    private double getDistance(double[] from, double[] to) {
        return GraphUtils.getDistance(from[0], from[1], to[0], to[1], hopper);
    }

    public synchronized void reloadPolygons() throws Exception {
        logger.warn("downloading areas...");
        CloseableHttpClient client = HttpClients.createMinimal();
        HttpGet httpGet = new HttpGet("http://db/taxi/rest/area/with/factors");
        httpGet.setHeader("Authorization", "Basic " + new String(Base64.getEncoder().encode("route:!23456".getBytes())));
        ResponseHandler<List<Area>> responseHandler = response -> {
            int status = response.getStatusLine().getStatusCode();
            if (status >= 200 && status < 300) {
                HttpEntity entity = response.getEntity();
                return entity != null ? RoutingService.MAPPER.readValue(entity.getContent(), typeReference) : null;
            } else {
                throw new ClientProtocolException("Unexpected response status: " + status);
            }
        };
        List<Area> areas = client.execute(httpGet, responseHandler);
        areas.stream().forEach(area -> {
            List<Point> points = new ArrayList<>();
            try {
                if (area.getLoc() != null &&
                        area.getLoc().getCoordinates() != null &&
                        area.getLoc().getCoordinates().size() > 0 &&
                        area.getLoc().getCoordinates().get(0).size() > 0) {
                    area.getLoc().getCoordinates().get(0).stream().forEach(p -> points.add(new Point(p.get(0).floatValue(), p.get(1).floatValue())));
                }
                PolygonWithDataImpl polygonWithData = new PolygonWithDataImpl(points, area.getName(), (float) area.getFactor(), area.getDescription());
                polygons.addPolygon(polygonWithData);
            } catch (Exception e) {
                logger.error("{} : {}", area.getName(), e);
            }
        });
        logger.warn("dowloaded!");
    }

    @PreDestroy
    public void preDestroy() {
        areaFetcher.interrupt();
        pool.shutdown();
    }

    class TaximeterLogger implements ru.macrobit.drivertaxi.taximeter.logs.TaximeterLogger {
        @Override
        public void logNewTaximeterLocation(TaximeterLocation taximeterLocation) {

        }

        @Override
        public void logTaximeterIntervalsOnFinish(ArrayList<TaximeterInterval> arrayList) {

        }

        @Override
        public void logNewIntervalData(TaximeterInterval taximeterInterval, int i) {
        }

        @Override
        public void clearTaximeterLocations() {

        }

        @Override
        public ArrayList<TaximeterLocation> getOrderLoggedTaximeterLocations() {
            return null;
        }
    }
}
