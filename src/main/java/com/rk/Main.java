package com.rk;

import com.mongodb.BasicDBObject;
import com.mongodb.client.*;
import org.bson.Document;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    static JSONObject parseGeo(Document geo) { // разбор геометрии в json
        JSONObject geoObj = new JSONObject();
        if (geo != null) {
            String type = (String) geo.get("type");
            if (!type.equals("WTF")) {
                ArrayList coors = (ArrayList) geo.get("coordinates");
                if (coors != null) {
                    geoObj.put("type", type);
                    geoObj.put("coordinates", coors);
                } else {
                    geoObj = null;
                }
            } else {
                geoObj = null;
            }
        }
        return geoObj;
    }

    public static void writeClusterToFile(JSONObject geometry, String filename, String objectId) throws IOException {
        File file = new File(filename);
        file.createNewFile();
        Writer wr = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);

        JSONArray features = new JSONArray();
        JSONObject featureCollection = new JSONObject();
        featureCollection.put("type", "FeatureCollection");
        JSONObject feature = new JSONObject();
        JSONObject properties = new JSONObject();

        feature.put("type", "Feature");
        feature.put("geometry", geometry);
        properties.put("ID", objectId);
        feature.put("properties", properties);
        features.add(feature);

        featureCollection.put("features", features);
        wr.write(featureCollection.toString());
        wr.flush();
        wr.close();
    }

    public static void main(String[] args) throws SQLException, ParseException, IOException, Exception {
        long startT = System.currentTimeMillis();
        try {
            LogManager.getLogManager().readConfiguration(Main.class.getResourceAsStream("logProperties"));
        } catch (IOException e) {
            System.err.println("Could not setup logger configuration: " + e.toString());
        }
        Logger log = Logger.getLogger(Main.class.getName());

        JSONParser parser = new JSONParser();
        Reader reader = new InputStreamReader(new FileInputStream("settings.json"), StandardCharsets.UTF_8);

        JSONObject obj = (JSONObject) parser.parse(reader);
        String ConnectionStringMongo = (String) obj.get("ConnectionStringMongo");
        JSONObject ConnectionStringPostgre = (JSONObject) obj.get("ConnectionStringPostgre");
        String URL = (String) ConnectionStringPostgre.get("URL");
        String User = (String) ConnectionStringPostgre.get("user");
        String Password = (String) ConnectionStringPostgre.get("password");
        ArrayList<String> priorityOrder = (ArrayList<String>) obj.get("priorityOrder");
        String Collection = (String) obj.get("collection");
        String outputFileName = (String) obj.get("outputFileName");

        JSONObject filtersJSON = (JSONObject) obj.get("filter");
        BasicDBObject filter = BasicDBObject.parse(filtersJSON.toString());

        String databaseName = (String) obj.get("database");
        boolean serverMode = (boolean) obj.get("serverMode");
        boolean writeOriginals = (boolean) obj.get("writeOriginals");

        String savePath = (String) obj.get("savePath");
        //String mergedGeomFolderPath = savePath + "\\merged";
        //String weightedGeomFolderPath = savePath + "\\weighted";
        String originalGeomFolderPath = savePath + "\\original";
        String centeredGeomFolderPath = savePath + "\\centered";

        //Files.createDirectories(Paths.get(mergedGeomFolderPath));
        //Files.createDirectories(Paths.get(weightedGeomFolderPath));

        if (!serverMode && writeOriginals) {
            Files.createDirectories(Paths.get(originalGeomFolderPath));
            Files.createDirectories(Paths.get(centeredGeomFolderPath));
        }
//
        
        File file = new File(outputFileName + ".geojson");
        file.createNewFile();
        Writer wr = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        
        JSONArray features = new JSONArray();
        JSONObject featureCollection = new JSONObject();
        featureCollection.put("type", "FeatureCollection");
        JSONObject feature = new JSONObject();
        

        File fileW = new File(outputFileName + "Weighted.geojson");
        fileW.createNewFile();
        Writer wrW = new OutputStreamWriter(new FileOutputStream(fileW), StandardCharsets.UTF_8);

        JSONArray featuresW = new JSONArray();
        JSONObject featureCollectionW = new JSONObject();
        featureCollectionW.put("type", "FeatureCollection");
        JSONObject featureW = new JSONObject();
        
        File file2 = new File("clustIN_for_" + outputFileName + ".geojson");
        file2.createNewFile();
        Writer wr2 = new OutputStreamWriter(new FileOutputStream(file2), StandardCharsets.UTF_8);

        JSONArray features2 = new JSONArray();
        JSONObject featureCollection2 = new JSONObject();
        featureCollection2.put("type", "FeatureCollection");
//

        Connection conn = DriverManager.getConnection(URL, User, Password);
        MongoClient mongo = MongoClients.create(ConnectionStringMongo);

        MongoDatabase db = mongo.getDatabase(databaseName);
        MongoCollection<Document> hClusters = db.getCollection(Collection);

        MongoCursor<Document> cursor = hClusters.find(filter).iterator();

        String queryMain = "{call rk_hcluster(?, ?, ?)}";
        CallableStatement ps = conn.prepareCall(queryMain);

        String queryCenter = "{call rk_center_geom(?, ?, ?)}";
        CallableStatement center_ps = conn.prepareCall(queryCenter);

        int countProcessed = 0; // счетчик обработанных объектов хранимкой
        int countWeighted = 0; // счетчик объектов выбранных по весу

        // хранимка для создания мультиполигона // больше не нужна
        /* String query = "{call rk_alignn(?)}";
        CallableStatement ps2 = conn.prepareCall(query);*/

        while (cursor.hasNext()) { // пробег по базе h_clusters
            Document d = cursor.next();

            int clusterId = (int) d.get("ID");
            String originalClusterFolder = originalGeomFolderPath + "\\" + String.valueOf(clusterId);
            String centeredClusterFolder = centeredGeomFolderPath + "\\" + String.valueOf(clusterId);

            if (!serverMode) {
                if (writeOriginals) {
                    Files.createDirectories(Paths.get(originalClusterFolder));
                }
                Files.createDirectories(Paths.get(centeredClusterFolder));
            }
            

            Map<String, List<JSONObject>> geometriesMap = new HashMap<>();
            final Set<String> idSet = new HashSet<>();

            final Set<String> sourcesSet = new HashSet<>();

            class Counter {
                int value = 1;
            }
            Counter counter = new Counter();

            Consumer<Document> extractVertexesFromCluster = (clusterDoc) -> { 
                ArrayList ids = (ArrayList) clusterDoc.get("allVertexes"); // поле в котором хранятся ссылки на объекты кластера
                // allVertexes - массив с записями в формате источник_id

                for (int i = 0; i < ids.size(); i++) { // проход по массиву с ссылками на объекты
                    // в конце этого цикла передать массив геометрий в пг и обновить его
                    String str = (String) ids.get(i);
                    if (idSet.contains(str)) {
                        continue;
                    }
                    idSet.add(str);

                    String[] s = str.split("_"); // название базы + id
                    String collection = s[0] + "_houses";

                    if (collection.equals("lands_houses")) { // исключаем ЗУ
                        sourcesSet.add(s[0]);
                        continue;
                    }

                    BasicDBObject IDfilter = new BasicDBObject();
                    IDfilter.put("ID", s[1]);
                    MongoCollection<Document> houses = db.getCollection(collection); // поиск документа в коллекции источника по id
                    MongoCursor<Document> c = houses.find(IDfilter).iterator();

                    if (c.hasNext()) { // получение геометрии
                        Document doc = c.next();
                        Document geoDoc = (Document) doc.get("Geometry");
                        JSONObject geoObj = parseGeo(geoDoc);
                        if (geoObj == null || geoObj.isEmpty()) {
                            continue;
                        }
                        sourcesSet.add(s[0]);
                        if (geoObj.get("type").equals("Point")) {
                            continue;
                        }


                        if (!geometriesMap.containsKey(s[0])) {
                            geometriesMap.put(s[0], new ArrayList<>());
                        }
                        geometriesMap.get(s[0]).add(geoObj);

                        // вывод нецентрированных объектов
                        if (!serverMode && writeOriginals) {
                            try {
                                writeClusterToFile(geoObj, originalClusterFolder + "\\" + String.valueOf(counter.value++) + ".geojson", s[0] + "_" + (String) doc.get("ID"));
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        JSONObject feature2 = new JSONObject();
                        JSONObject properties2 = new JSONObject();
                        feature2.put("type", "Feature");
                        feature2.put("geometry", geoObj);
                        properties2.put("ID", clusterDoc.getObjectId("_id").toString());
                        feature2.put("properties", properties2);
                        features2.add(feature2);
                    }
                } // конец цикла для пробега по массиву с ссылками на источники
            };
            extractVertexesFromCluster.accept(d);
            if (d.getInteger("secondClusterID") != null) {
                int secondClusterId = d.getInteger("secondClusterID");
                d = cursor.next();
                if (!d.getInteger("ID").equals(secondClusterId)) {
                    throw new Exception("got another second cluster");
                }
                extractVertexesFromCluster.accept(d);
            }

            //List<String> finalGeometriesSources = new ArrayList<>();
            Map<String, String> finalGeometries = new HashMap<>();
            for (Map.Entry<String, List<JSONObject>> entry : geometriesMap.entrySet()) {
                // сборка объектов из одного источника в мультиполигон
                String coor = "{\"type\": \"MultiPolygon\", \"coordinates\": [";
                for (JSONObject jsonObject : entry.getValue()) {
                    coor = coor + jsonObject.get("coordinates").toString() + ',';
                }
                String t = coor.substring(0, coor.length() - 1);
                t = t + ']' + '}';
                JSONObject Multi = (JSONObject) parser.parse(t);
                finalGeometries.put(entry.getKey(), Multi.toString());
            }

            // передать массив в пг получить ответ обновить массив

            // определение номер приоритетного источника в массиве


            Object[] sourcesForCentrArr = finalGeometries.keySet().toArray();
            int sourceNum = -1;
            for (int i = 0; i < priorityOrder.size() && sourceNum == -1; i++) {
                if (finalGeometries.containsKey(priorityOrder.get(i))) {
                    for (int j = 0; j < sourcesForCentrArr.length; j++) {
                        if (priorityOrder.get(i).equals(sourcesForCentrArr[j])) {
                            sourceNum = j;
                            break;
                        }
                    }
                }
            }
            
            // может быть полезно, если не нужно отправлять объекты одного источника в БД
            /* 
            if (finalGeometries.size() == 1 && sourcesSet.size() > 1) {
                BasicDBObject weightFilter = new BasicDBObject();
                weightFilter.put("ID", d.get(sourcesForCentrArr[0] + "ID"));
                MongoCollection<Document> houses = db.getCollection(sourcesForCentrArr[0] + "_houses"); // поиск документа в коллекции источника по id
                MongoCursor<Document> c = houses.find(weightFilter).iterator();
                while (c.hasNext()) { // получение геометрии
                    Document doc = c.next();
                    Document geoDoc = (Document) doc.get("Geometry");
                    JSONObject geoObj = parseGeo(geoDoc);
                    if (geoObj != null && !geoObj.isEmpty()) {
                        if (!geoObj.get("type").equals("Point")) {
                            countWeighted++;
                            JSONObject properties = new JSONObject();
                            feature.put("type", "Feature");
                            feature.put("geometry", geoObj);
                            properties.put("ID", clusterId);
                            feature.put("properties", properties);
                            features.add(feature);
                            feature = new JSONObject();
                            break;
                        }
                    }
                }
            }
            */

            if (finalGeometries.size() > 1 || finalGeometries.size() == 1 && sourcesSet.size() > 1) {
                //
                final Array stringsArray = conn.createArrayOf(
                        "varchar",
                        finalGeometries.values().toArray(new String[finalGeometries.size()]));

                // вывод центрированных объектов
                if (!serverMode) {
                    center_ps.setArray(1, stringsArray);
                    center_ps.setInt(2, finalGeometries.size());
                    center_ps.setInt(3, sourceNum+1);

                    ResultSet center_rs = center_ps.executeQuery();
                    while (center_rs.next()) {
                        Array a = center_rs.getArray(1);
                        String[] sources_centered = (String[]) a.getArray();
                        for (int i = 0; i < sources_centered.length; ++i) {
                            writeClusterToFile((JSONObject) parser.parse(sources_centered[i]), centeredClusterFolder + "\\" + sourcesForCentrArr[i] + ".geojson", ""); 
                        }
                        
                    }
                }
                
                ps.setArray(1, stringsArray);
                ps.setInt(2, finalGeometries.size());
                ps.setInt(3, sourceNum+1);

                


                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    if ((rs.getString(1) != null)) {
                        JSONObject g2 = (JSONObject) parser.parse(rs.getString(1));
// запись построенных объектов
                        countProcessed++;
                        JSONObject properties = new JSONObject();
                        feature.put("type", "Feature");
                        feature.put("geometry", g2);
                        properties.put("ID", clusterId);
                        feature.put("properties", properties);
                        features.add(feature);
                        feature = new JSONObject();

                    } else {
// запись взвешенных объектов
                        ArrayList<String> sourcesWeightList = new ArrayList<>();
                        // cписок строк название источника + Weight
                        for (int i = 0; i < sourcesForCentrArr.length; i++) {
                            sourcesWeightList.add(sourcesForCentrArr[i] + "Weight");
                        }
                        TreeMap<Double, String> weightsSortedMap = new TreeMap<>();
                         
                        for (int i = 0; i < sourcesWeightList.size(); i++) {
                            Object temp = d.get(sourcesWeightList.get(i)); // получение веса
                            if (temp.getClass().equals(java.lang.Double.class)) {
                                weightsSortedMap.put(d.getDouble(sourcesWeightList.get(i)), sourcesWeightList.get(i));
                            } else if (temp.getClass().equals(java.lang.Integer.class)) {
                                weightsSortedMap.put(Double.valueOf(d.getInteger(sourcesWeightList.get(i))), sourcesWeightList.get(i));
                            } else {
                                System.out.println(String.valueOf(clusterId) + ": " + sourcesWeightList.get(i) + " type is unusual");
                            }
                        }
                        
                        ArrayList<String> sourcesNames = new ArrayList<String>(weightsSortedMap.values());
                        //ArrayList<Double> sourcesWeights = new ArrayList<Double>(weightsSortedMap.keySet());

                        
                        boolean isMax = false;
                        for (int i = sourcesNames.size() - 1; i > -1 && !isMax; i--) {
                            BasicDBObject weightFilter = new BasicDBObject();
                            weightFilter.put("ID", d.get(sourcesNames.get(i).replace("Weight", "ID")));
                            MongoCollection<Document> houses = db.getCollection(sourcesNames.get(i).replace("Weight", "_houses")); // поиск документа в коллекции источника по id
                            MongoCursor<Document> c = houses.find(weightFilter).iterator();

                            while (c.hasNext()) { // получение геометрии
                                Document doc = c.next();
                                Document geoDoc = (Document) doc.get("Geometry");
                                JSONObject geoObj = parseGeo(geoDoc);
                                if (geoObj != null && !geoObj.isEmpty()) {
                                    if (!geoObj.get("type").equals("Point")) {
                                        countWeighted++;
                                        isMax = true;
                                        featureW.put("type", "Feature");
                                        featureW.put("geometry", geoObj);

                                        JSONObject properties = new JSONObject();
                                        properties.put("ID", clusterId);
                                        featureW.put("properties", properties);

                                        featuresW.add(featureW);
                                        featureW = new JSONObject();
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

//
        featureCollection.put("features", features);
        wr.write(featureCollection.toString());
        wr.flush();
        wr.close();

        featureCollectionW.put("features", featuresW);
        wrW.write(featureCollectionW.toString());
        wrW.flush();
        wrW.close();

        featureCollection2.put("features", features2);
        wr2.write(featureCollection2.toString());
        wr2.flush();
        wr2.close();

        mongo.close();
        conn.close();
        long finish = System.currentTimeMillis();
        long elapsed = finish - startT;

        log.log(Level.SEVERE, "The count of processed by function objects: " + countProcessed);
        log.log(Level.SEVERE, "The count of objects chosen by weight: " + countWeighted);
        log.log(Level.SEVERE, "Duration, seconds: " + elapsed / 1000);
        System.out.println("Прошло времени, с: " + elapsed / 1000);
    }
}
