package moonbox.catalyst.adapter.elasticsearch5.client

import java.io._
import java.util
import java.util.ArrayList

import moonbox.catalyst.adapter.elasticsearch5.client.AggWrapper.AggregationType
import org.apache.spark.sql.types.{DataType, DataTypes, StructType}
import org.elasticsearch.client.RestClientBuilder
import org.json.JSONArray

import scala.collection.mutable

//import com.alibaba.fastjson.{JSON, JSONArray, JSONObject}
import org.apache.http.entity.{ContentType, StringEntity}
import org.apache.http.{HttpEntity, HttpHost}
import org.elasticsearch.client.{Response, RestClient}
import org.json.JSONObject


case class ShapeType(name: String, tpe: AnyRef, level: Int)

class EsRestClient(param: Map[String, String]) {

    val nodes: Array[String] = param("nodes").split(",")   // 1.1.1.1:9200,2.2.2.2:9200
    //val port = param.getOrElse("es.port", "9200")
    val user: Option[String] = param.get("user")
    val password: Option[String] = param.get("password")

    //TODO: more shape type
    val geoShapeMap = Set("POINT", "LINE_STRING", "POLYGON", "MULTI_POINT", "MULTI_LINE_STRING",
        "MULTI_POLYGON",  "GEOMETRY_COLLECTION", "ENVELOPE", "CIRCLE"
    )
    //TODO: more point type
    val geoPointMap = Set("LAT_LON_OBJECT",  "LAT_LON_STRING", "GEOHASH", "LON_LAT_ARRAY" )

    val restClient: RestClient = {
        val httpHost: Array[HttpHost] = nodes.map{node =>
            val array: Array[String] = node.split(":")
            new HttpHost(array(0), Integer.valueOf(array(1)), "http")
        }

        //https://www.elastic.co/guide/en/elasticsearch/client/java-rest/5.3/_basic_authentication.html
        if(user.isDefined && password.isDefined) {
            import org.apache.http.impl.client.BasicCredentialsProvider
            import org.apache.http.auth.AuthScope
            import org.apache.http.auth.UsernamePasswordCredentials
            import org.apache.http.impl.nio.client.HttpAsyncClientBuilder

            val credentialsProvider = new BasicCredentialsProvider
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(user.get, password.get))
            //support user and password for auth in ES
            RestClient.builder(httpHost: _*).setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                override def customizeHttpClient(httpClientBuilder: HttpAsyncClientBuilder): HttpAsyncClientBuilder = {
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider)
                }
            }).build()

        }else {
            RestClient.builder(httpHost: _*).build()
        }

    }

    def getContent(response: Response): String = {
        val entityRsp: InputStream = response.getEntity.getContent

        val out = new ByteArrayOutputStream()
        val buffer = new Array[Byte](1024)
        var len = entityRsp.read(buffer, 0, buffer.length)
        while(len != -1){
            out.write(buffer, 0, len)
            len = entityRsp.read(buffer, 0, buffer.length)
        }

        val jsonStr = new String(out.toByteArray)
        entityRsp.close()
        //println(jsonStr)
        jsonStr
    }

    /*{
        "name" : "node-1",
        "cluster_name" : "edp-es",
        "cluster_uuid" : "4cFGDzEQRD2HCSzV8442Mg",
        "version" : {
            "number" : "5.3.2",
            "build_hash" : "3068195",
            "build_date" : "2017-04-24T16:15:59.481Z",
            "build_snapshot" : false,
            "lucene_version" : "6.4.2"
        },
        "tagline" : "You Know, for Search"
    }*/

    def getIndices(): Seq[String] = {
        val response = restClient.performRequest("GET", "/_aliases", new util.Hashtable[String, String]())
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        if(isSucceeded(response)){
            import scala.collection.JavaConversions._
            jsonObject.keySet.toSeq
        }else{
            Seq.empty[String]
        }
    }

    def getIndicesAndType(): Seq[(String, String)] = {
        val response: Response = restClient.performRequest("GET", s"""/_mapping?pretty=true""", new util.Hashtable[String, String]())
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        if(isSucceeded(response)) {
            import scala.collection.JavaConversions._
            jsonObject.keySet.flatMap { index =>
                val body = jsonObject.getJSONObject(index)
                val mapping :JSONObject = body.getJSONObject("mappings")
                mapping.keySet().map { tpe => (index, tpe) }.toSeq
            }.toSeq

        }else {
            Seq.empty[(String, String)]
        }
    }


    /*
     * https://www.elastic.co/guide/en/elasticsearch/reference/5.3/indices-stats.html
     */
    def getStats(index: String): (Long, Long) = {  //(doc num, doc size)
        val response = restClient.performRequest("GET", s"$index/_stats/docs,store", new util.Hashtable[String, String]())
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        if(isSucceeded(response)){
            val count = getFieldAsLong(jsonObject, s"indices/${index}/total/docs/count")
            val size = getFieldAsLong(jsonObject, s"indices/${index}/total/store/size_in_bytes")
            (count, size)
        }else{
            (0L, 0L)
        }
    }

    def getVersion(): Seq[Int] = {
        val response = restClient.performRequest("GET", "", new util.Hashtable[String, String]())
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        if(isSucceeded(response)){
            val number = jsonObject.getJSONObject("version").get("number").toString
            number.split('.').map(_.toInt)
        }else{
            Seq(5,3,2) //default version
        }
    }

    def getSchema(index: String, mtype: String) : (StructType, Set[String]) = {
        val response: Response = restClient.performRequest("GET", s"""/$index/_mapping/$mtype""", new util.Hashtable[String, String]())
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        if(isSucceeded(response)){
            val mapping: JSONObject = getMapping(jsonObject, index, mtype)
            if(mapping == null) {
                throw new Exception("getSchema: communicate with es error1")
            }
            val nestSet = mutable.Set[String]()
            val prop = getProperity(index, mtype, mapping, "", nestSet)
            (prop, nestSet.toSet)
        }else {
            throw new Exception("getSchema: communicate with es error2")
        }

    }


    def getProperity(index: String, mtype: String, mapping: JSONObject, parent:String = "", nestField: mutable.Set[String] = mutable.Set.empty[String]): StructType = {
        val arrayInclude = param.get("es.read.field.as.array.include")
        val arraySet = if(arrayInclude.isDefined) {
            arrayInclude.get.split(",").map{_.stripSuffix(" ").stripPrefix(" ")}.toSet
        } else {
            Set.empty[String]
        }

        //es.mapping.date.rich
        val dateRich = param.get("es.mapping.date.rich")
        val dateTimeStamp = if(dateRich.isDefined) {
            dateRich.get == "true"
        }else {
            true //By default this is true
        }

        import org.apache.spark.sql.types._
        val properties = if(mapping.has("properties")){
            mapping.getJSONObject("properties")
        }else{
            mapping
        }
        var seq: Seq[StructField] = Seq.empty[StructField]
        import scala.collection.JavaConversions._

        for(prop <- properties.keySet if prop != "fielddata") {
            val rspType = properties.get(prop) match {
                case j:JSONObject => j.optString("type", "object")
                case s: String => s
            }
            if(rspType == "nested"){
                nestField.add(prop)  //save nest field, for get right result in json request
            }

            //IF a column is an array, it must be config to array. Nest could by detected from config
            val stype = if(arraySet.contains(prop)) { //array OR nest type only have one
                "array"
            }else{
                rspType
            }

            val dtype: DataType = stype match {
                case "text"    => StringType
                case "boolean" => BooleanType
                case "double"  => DoubleType
                case "binary"  => BinaryType
                case "short"   => ShortType
                case "float"   => FloatType
                case "integer" => IntegerType
                case "long"    => LongType
                case "keyword" => StringType
                case "date"    => if(dateTimeStamp) TimestampType else StringType       //date
                case "array"   => ArrayType(getProperity(index, mtype, properties.getJSONObject(prop), "", nestField)) // array
                case "nested"  =>
                    //nestField.add(prop)  //save nest field
                    getProperity(index, mtype, properties.getJSONObject(prop))     // nest
                case "object"  => getProperity(index, mtype, properties.getJSONObject(prop), prop, nestField)     // object
                case "geo_point" =>
                    val field = if(parent.isEmpty) prop else s"$parent.$prop"
                    val pointTypeOpt = sampleGeoField(index, mtype, field, "geo_point")
                    if(pointTypeOpt.isDefined){
                        val tye = pointTypeOpt.get
                        tye match {
                            case "LON_LAT_ARRAY"    => DataTypes.createArrayType(DoubleType)
                            case "GEOHASH"          => StringType
                            case "LAT_LON_STRING"   => StringType
                            case "LAT_LON_OBJECT"   =>
                                val lon = DataTypes.createStructField("lat", DoubleType, true)
                                val lat = DataTypes.createStructField("lon", DoubleType, true)
                                DataTypes.createStructType(Array(lon,lat))
                        }
                    }else{
                        throw new Exception("error to get geo point")
                    }
                    //"location" : { "lat" : 40.12, "lon" : -71.34 }
                case "geo_shape" =>
                    val field = if(parent.isEmpty) prop else s"$parent.$prop"
                    val shapeTypeOpt= sampleGeoField(index, mtype, field, "geo_shape")
                    if(shapeTypeOpt.isDefined){
                        val fields = new ArrayList[StructField]()
                        fields.add(DataTypes.createStructField("type", StringType, true))
                        val coordinate = "coordinates"
                        val shapeType = shapeTypeOpt.get
                        shapeType match {
                            case "POINT" =>  fields.add(DataTypes.createStructField(coordinate, DataTypes.createArrayType(DoubleType), true))
                            case "LINE_STRING" => fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 2), true))
                            case "POLYGON"  =>{
                                fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 3), true))
                                fields.add(DataTypes.createStructField("orientation", StringType, true))
                            }
                            case "MULTI_POINT"  =>fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 2), true))
                            case "MULTI_LINE_STRING"  => fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 3), true))
                            case "MULTI_POLYGON"  => fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 4), true))
                            case "GEOMETRY_COLLECTION"  => throw new Exception(s"Geoshape GEOMETRY_COLLECTION not supported")
                            case "ENVELOPE"  => fields.add(DataTypes.createStructField(coordinate, createNestedArray(DoubleType, 2), true))
                            case "CIRCLE"  => {
                                fields.add(DataTypes.createStructField(coordinate, DataTypes.createArrayType(DoubleType), true))
                                fields.add(DataTypes.createStructField("radius", StringType, true))
                            }
                        }  //[13.400544, 52.530286]
                        val geoShape = DataTypes.createStructType(fields)
                        geoShape
                    }
                    else{
                        throw new Exception("error to get geo shape")
                    }
                case _         => StringType

            }
            seq = seq :+ StructField(prop, dtype)
        }
        StructType(seq.toArray)
    }

    def createNestedArray(elementType: DataType, depth: Int): DataType = {
        var array = elementType
        for (_ <- 0 until depth) {
            array = DataTypes.createArrayType(array)
        }
        array
    }

    def sampleGeoField(index: String, mtype: String, field: String, geoType: String): Option[String] = {

        val data = sampleFieldData(index, mtype, field)  //query

        if(data.isDefined) {  //parse geo info
            val geoValue = data.get
            if (geoType == "geo_point") {
                if (geoValue.isInstanceOf[java.util.List[Any]]){
                    val list = geoValue.asInstanceOf[java.util.List[Any]]
                    val content = list.get(0)
                    if(content.isInstanceOf[Double]){
                        return Some("LON_LAT_ARRAY")
                    }
                }
                else if (geoValue.isInstanceOf[String]) {
                    val geoStr = geoValue.asInstanceOf[String]
                    if(geoStr.contains(",")) {
                        return Some("LAT_LON_STRING")
                    }else{
                        return Some("GEOHASH")
                    }
                }else if(geoValue.isInstanceOf[java.util.Map[String, Any]]){
                    return Some("LAT_LON_OBJECT")
                }
            }

            if (geoType == "geo_shape") {
                if(geoValue.isInstanceOf[java.util.Map[String, Any]]) {
                    val map = geoValue.asInstanceOf[java.util.Map[String, Any]]
                    val typ = map.get("type").toString
                    return Some(typ.toUpperCase)
                }
            }
        }
        None
    }


    def sampleFieldData(index: String, mtype: String, fields: String): Option[AnyRef] = {
        val request: String =
            s"""
              |{ "terminate_after":1, "size":1,
              |  "_source" : ["$fields"],
              |  "query": {
              |      "bool": {"must" : [
              |          {"exists": {"field": "$fields"} }
              |       ]}
              |   }
              |}
            """.stripMargin
        val entityReq: HttpEntity = new StringEntity(request, ContentType.APPLICATION_JSON)
        val response: Response = restClient.performRequest("GET", s"""/$index/$mtype/_search""", new util.Hashtable[String, String](), entityReq)
        val jsonStr = getContent(response)
        val jsonObject = new JSONObject(jsonStr)
        val map = scala.collection.mutable.Map.empty[String, AnyRef]

        if(isSucceeded(response)){
            val hits = getFieldAsArray(jsonObject, "hits/hits")
            val iter = hits.iterator

            if (iter.hasNext) {
                val hit = iter.next.asInstanceOf[JSONObject]

                if (hit.opt("_source") != null) { //for source,  select col from table
                    val data = hit.opt("_source")
                    val jsonObject = data.asInstanceOf[JSONObject]
                    getHitsValue(jsonObject, "", map)
                }

                if (hit.opt("fields") != null) { //for script_fields, select col as aaa from table
                    val data = hit.opt("fields")
                    val jsonObject = data.asInstanceOf[JSONObject]
                    getHitsValue(jsonObject, "", map)
                }
            }

        }
        val field: Seq[String] = fields.split('.').toSeq
        if(field.length == 1) {
            map.get(field.head)
        }else {
            var ret = map(field.head).asInstanceOf[java.util.Map[String, AnyRef]]
            for(i <- 1 until field.length) {
                ret = ret.get(field(i)).asInstanceOf[java.util.Map[String, AnyRef]]
            }
            Some(ret)
        }

    }

    def getMapping(result: JSONObject, index: String, mtype: String) = {
        if(result == null) null
        try {
            val a = result.getJSONObject(index).getJSONObject("mappings").getJSONObject(mtype)
            a
        }catch {
            case e: Exception => null
        }
    }


    def performScrollFirst(index: String, mtype: String, query: String, limit: Boolean=true): (ActionResponse, String, Long, Long, Boolean) = {
        val actionRsp: ActionResponse = new ActionResponse()

        val entityReq: HttpEntity = new StringEntity(query, ContentType.APPLICATION_JSON)   //default 2 min wait
        val response: Response = restClient.performRequest("POST", s"""/$index/$mtype/_search?scroll=2m""", new util.Hashtable[String, String](), entityReq)
        if(!isSucceeded(response)) {
            actionRsp.succeeded(false)
            throw new Exception("performScrollRequest to Server return ERROR " +response.getStatusLine.getStatusCode)
        }

        val content = getContent(response)
        val jsonObject = new JSONObject(content)
        val totalLines = getFieldAsLong(jsonObject, "hits/total")
        val scrollId :String = getFieldAsString(jsonObject, "_scroll_id")
        val fetchSize: Long = handleResponse(content, actionRsp)   //handle

        val finished = if(containsAggs(jsonObject)) {
            false //agg no scroll query
        }else {
            true
        }

        (actionRsp, scrollId, totalLines, fetchSize, finished)
    }

    def performScrollLast(scrollId: String) :(ActionResponse, String, Long) = {

        val actionRsp: ActionResponse = new ActionResponse()
        val leftQuery = s"""{"scroll":"2m","scroll_id":"$scrollId"}"""
        val leftScrollReq: HttpEntity = new StringEntity(leftQuery, ContentType.APPLICATION_JSON)

        val response: Response = restClient.performRequest("POST", s"""/_search/scroll""", new util.Hashtable[String, String](), leftScrollReq)

        if(!isSucceeded(response)) {
            actionRsp.succeeded(false)
            throw new Exception(s"performScrollLast to Server return ERROR ${response.getStatusLine.getStatusCode} line")
        }

        val content = getContent(response)
        val jsonObject = new JSONObject(content)
        val newScrollId = getFieldAsString(jsonObject, "_scroll_id")  //update scroll id
        val fetchSize = handleResponse(content, actionRsp)      //update fetch size

        (actionRsp, newScrollId, fetchSize)
    }

    def performScrollRequest(index: String, mtype: String, query: String, limit: Boolean=true) : ActionResponse = {
        val actionRsp: ActionResponse = new ActionResponse()

        val entityReq: HttpEntity = new StringEntity(query, ContentType.APPLICATION_JSON)   //default 2 min wait
        val response: Response = restClient.performRequest("POST", s"""/$index/$mtype/_search?scroll=2m""", new util.Hashtable[String, String](), entityReq)
        if(!isSucceeded(response)) {
            actionRsp.succeeded(false)
            throw new Exception("performScrollRequest to Server return ERROR " +response.getStatusLine.getStatusCode)
        }

        val content = getContent(response)
        val jsonObject = new JSONObject(content)
        val totalLines = getFieldAsLong(jsonObject, "hits/total")
        var scrollId = getFieldAsString(jsonObject, "_scroll_id")

        var proceedLines: Long = 0L
        var fetchSize: Long = handleResponse(content, actionRsp)   //handle
        proceedLines += fetchSize

        if(containsAggs(jsonObject)){
            actionRsp   //agg no scroll query
        }
        else {
            while (proceedLines < totalLines && proceedLines != 0 && !limit) {
                val leftQuery = s"""{"scroll":"2m","scroll_id":"$scrollId"}"""
                val leftScrollReq: HttpEntity = new StringEntity(leftQuery, ContentType.APPLICATION_JSON)
                val response: Response = restClient.performRequest("POST", s"""/_search/scroll""", new util.Hashtable[String, String](), leftScrollReq)
                if(!isSucceeded(response)) {
                    actionRsp.succeeded(false)
                    throw new Exception(s"performScrollRequest to Server return ERROR ${response.getStatusLine.getStatusCode} line $proceedLines")
                }

                scrollId = getFieldAsString(jsonObject, "_scroll_id")  //update scroll id
                fetchSize = handleResponse(getContent(response), actionRsp)      //update fetch size
                proceedLines += fetchSize
            }
            actionRsp
        }
    }


    def handleResponse(response: String, action: ActionResponse): Long = {
        var fetchSize: Long = 0
        val jsonObject = new JSONObject(response)

        val total = getFieldAsLong(jsonObject, "hits/total")
        action.totalHits(total)

        if(containsAggs(jsonObject)){
            var aggregationsMap:JSONObject =jsonObject.getJSONObject("aggregations")
            if(aggregationsMap == null) {
                aggregationsMap = jsonObject.getJSONObject("aggs")
            }
            import scala.collection.JavaConversions._
            val map = scala.collection.mutable.Map.empty[String, AnyRef]
            var aggIsSimple = false
            for (key <- aggregationsMap.keySet) {
                val aggResult = aggregationsMap.getJSONObject(key)
                if (aggResult.has("buckets")) { // Multi-bucket aggregations
                    val buckets = aggResult.getJSONArray("buckets").iterator
                    map.clear()
                    while ( buckets.hasNext) {
                        val bucketValue = buckets.next.asInstanceOf[JSONObject]
                        getBucketValue(bucketValue, key, map)  //recv get value
                        fetchSize += map.size
                        action.addAggregation(
                            new AggWrapper(AggregationType.MULTI_BUCKETS, bucketValue.toString, Map.empty[String, AnyRef] ++ map))
                    }
                }
                else {
                    val value: AnyRef = getAggValue(aggResult)
                    map +=(key -> value)
                    aggIsSimple = true

                }//Keep only one aggregation
            }
            if(aggIsSimple) {  //add in one line for simple agg, select count(x), max(y) from from table
                fetchSize += map.size
                action.addAggregation(new AggWrapper(AggregationType.SIMPLE, aggregationsMap.toString, Map.empty[String, AnyRef] ++ map))
            }

        }
        else {
            val hits = getFieldAsArray(jsonObject, "hits/hits")
            val iter = hits.iterator
            val map = scala.collection.mutable.Map.empty[String, AnyRef]

            while (iter.hasNext) {
                map.clear()
                val hit = iter.next.asInstanceOf[JSONObject]

                if (hit.opt("_source") != null) { //for source,  select col from table
                    val data = hit.opt("_source")
                    val jsonObject = data.asInstanceOf[JSONObject]
                    getHitsValue(jsonObject, "", map)
                }

                if (hit.opt("fields") != null) {  //for script_fields, select col as aaa from table
                    val data = hit.opt("fields")
                    val jsonObject = data.asInstanceOf[JSONObject]
                    getHitsValue(jsonObject, "", map)
                }

                if(map.size != 0) {  //es sort will return one empty line, do not add it
                    action.addHit(new HitWrapper(hit.getString("_index"),
                        hit.getString("_type"),
                        hit.getString("_id"),
                        "", //TODO: no use here
                        Map.empty[String, AnyRef] ++ map))
                }
                fetchSize += 1  //for not loop forever
            }
        }

        fetchSize
    }

    def performRequest(index: String, mtype: String, query: String): ActionResponse = {
        val entityReq: HttpEntity = new StringEntity(query, ContentType.APPLICATION_JSON)

        val response: Response = restClient.performRequest("POST", s"""/$index/$mtype/_search""", new util.Hashtable[String, String](), entityReq)
        val jsonStr = getContent(response)
//        val gson = new GsonBuilder().setPrettyPrinting().create
//        val jsonObject = gson.fromJson(jsonStr, classOf[JSONObject])
        val jsonObject = new JSONObject(jsonStr)

        val rsp: ActionResponse = new ActionResponse()
        if(isSucceeded(response)){

            val total = getFieldAsLong(jsonObject, "hits/total")
            rsp.totalHits(total)

            if(containsAggs(jsonObject)){
                var aggregationsMap:JSONObject =jsonObject.getJSONObject("aggregations")
                if(aggregationsMap == null) {
                    aggregationsMap = jsonObject.getJSONObject("aggs")
                }
                import scala.collection.JavaConversions._
                val map = scala.collection.mutable.Map.empty[String, AnyRef]
                var aggIsSimple = false
                for (key <- aggregationsMap.keySet) {
                    val aggResult = aggregationsMap.getJSONObject(key)
                    if (aggResult.has("buckets")) { // Multi-bucket aggregations
                        val buckets = aggResult.getJSONArray("buckets").iterator
                        map.clear()
                        while ( buckets.hasNext) {
                            val bucketValue = buckets.next.asInstanceOf[JSONObject]
                            getBucketValue(bucketValue, key, map)  //recv get value
                            rsp.addAggregation(  new AggWrapper(AggregationType.MULTI_BUCKETS, bucketValue.toString, Map.empty[String, AnyRef] ++ map))
                        }
                    }
                    else {
                        val value: AnyRef = getAggValue(aggResult)
                        map +=(key -> value)
                        aggIsSimple = true

                    }//Keep only one aggregation
                }
                if(aggIsSimple) {  //add in one line for simple agg, select count(x), max(y) from from table
                    rsp.addAggregation(new AggWrapper(AggregationType.SIMPLE, aggregationsMap.toString, Map.empty[String, AnyRef] ++ map))
                }

            }
            else {
                import scala.collection.JavaConversions._
                val hits = getFieldAsArray(jsonObject, "hits/hits")
                val iter = hits.iterator
                val map = scala.collection.mutable.Map.empty[String, AnyRef]

                while (iter.hasNext) {
                    val hit = iter.next.asInstanceOf[JSONObject]

                    if (hit.opt("_source") != null) { //for source,  select col from table
                        val data = hit.opt("_source")
                        val jsonObject = data.asInstanceOf[JSONObject]
                        getHitsValue(jsonObject, "", map)
                    }

                    if (hit.opt("fields") != null) {  //for script_fields, select col as aaa from table
                        val data = hit.opt("fields")
                        val jsonObject = data.asInstanceOf[JSONObject]
                        getHitsValue(jsonObject, "", map)
                    }

                    rsp.addHit(
                        new HitWrapper(hit.getString("_index"),
                                hit.getString("_type"),
                                hit.getString("_id"),
                                "",  //TODO: no use here
                                Map.empty[String, AnyRef] ++ map))
                }
            }
        }
        else {
            if(response.getStatusLine.getStatusCode == 404) {
                new ActionResponse().succeeded(false)
            }
            else {
                throw new Exception(s"${jsonObject.get("error").toString}")
            }
        }
        rsp
    }

    def close(): Unit = {
        if(restClient != null) {
            restClient.close()
        }
    }

    def getAggValue(result: JSONObject): AnyRef = {
        if(result.has("value")){
            result.get("value")
        }else {
          null
        }
    }

    def getHitsValue(input: AnyRef, pkey: String, map: scala.collection.mutable.Map[String, AnyRef]): AnyRef = {
        import scala.collection.JavaConversions._
        val localMap = new java.util.HashMap[String, AnyRef]()
        input match {
            case jsonObject: JSONObject =>
                for(key <- jsonObject.keySet()) {
                    val value = jsonObject.get(key)
                    value match {
                        case j: JSONObject =>
                            val retMap = getHitsValue(j, key, map)
                            localMap.put(key, retMap)
                        case a: JSONArray =>  //TODO: [ -73.983, 40.719 ] or "user" -> [{"last":"Smith","first":"John"},{"last":"White","first":"Alice"}]
                            val iter: Iterable[AnyRef] = a.map{elem => getHitsValue(elem, key, map)}
                            val localArray = new java.util.ArrayList[AnyRef]()
                            localArray ++= iter
                            localMap.put(key, localArray)
                        case _  =>
                            localMap.put(key, value)
                    }
                }
                if(pkey.isEmpty) {  //only highest outer
                    map ++= localMap
                }
                localMap
            case e: AnyRef => e
        }
    }


    def getBucketValue(result: JSONObject, jsonKey: String, map: scala.collection.mutable.Map[String, AnyRef]): Unit = {
        import scala.collection.JavaConversions._
        for(key <- result.keySet()) {
            val value = result.get(key)
            value match {
                case j: JSONObject =>
                    val jsonObject = result.getJSONObject(key)
                    if(jsonObject.has("buckets")){
                        val buckets = jsonObject.getJSONArray("buckets").iterator()
                        while(buckets.hasNext){
                            val bucketValue = buckets.next().asInstanceOf[JSONObject]
                            getBucketValue(bucketValue, key, map)
                        }
                    }else if(jsonObject.has("value")) {
                        map += (key -> jsonObject.get("value"))
                    }
                case _ => if(key == "key") {  //select distinct column from table
                    map += (jsonKey -> value) }
            }

        }


    }


    def isSucceeded(response: Response): Boolean = {
        response.getStatusLine.getStatusCode >= 200 && response.getStatusLine.getStatusCode < 300
    }

    def containsTerminal(result: JSONObject): Boolean = {
        if(result != null && result.has("terminated_early")){
            val terminal = result.opt("terminated_early").asInstanceOf[Boolean]
            if(terminal) {
                return true
            }
        }
        false
    }

    def containsAggs(result: JSONObject): Boolean = {
        result != null && (result.has("aggregations") || result.has("aggregation") || result.has("aggs") || result.has("agg"))
    }

    def getParentField(parent: JSONObject, fields: Seq[String]): JSONObject = {
        var obj = parent
        var i = 0
        fields.foreach { field =>
            if( i + 1 < fields.length) {
                obj = obj.getJSONObject(field)
                i = i + 1
            }
        }
        obj
    }

    private def getFieldAsArray(obj: JSONObject, field: String) = {
        val fields = field.split("/")
        val parent = getParentField(obj, fields)
        parent.getJSONArray(fields(fields.length - 1))
    }

    private def getFieldAsString(jsonObject: JSONObject, field: String) = {
        jsonObject.get(field).toString
    }

    private def getFieldAsLong(jsonObject: JSONObject, field: String) = {
        val fields = field.split("/")
        val obj = getParentField(jsonObject, fields)
        obj.getLong(fields(fields.length - 1))
    }

}


object EsRestClient{

}
