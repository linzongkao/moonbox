package moonbox.catalyst.adapter.elasticsearch5.jdbc

import moonbox.catalyst.adapter.elasticsearch5.client.EsRestClient
import moonbox.catalyst.adapter.jdbc.JdbcSchema
import moonbox.catalyst.core.TableMetaData

class EsCatalystSchema(props: Map[String, String]) extends JdbcSchema(props) {

    val client = new EsRestClient(props)

    override def getFunctionNames = {
        Seq("geo_distance",
            "geo_shape",
            "geo_bounding_box",
            "geo_polygon",
            "array_exists",
            "array_filter",
            "array_map") ++ super.getFunctionNames
    }

    override def getTableMetaData(name: String): TableMetaData = {
        val index = name.split('/')(0)
        val tpe = name.split('/')(1)
        new EsCatalystTableMetaData(client, index, tpe)
    }

    override def getTableNames: Seq[String] = {
        client.getIndices()
    }

    //index + type
    override def getTableNames2: Seq[(String, String)] = {
        client.getIndicesAndType()
    }

    override def getVersion: Seq[Int] = {
        client.getVersion()
    }

    override def finalize() :Unit = {
        super.finalize()
        client.close()
    }

}

object EsCatalystSchema {
    def main(args :Array[String]) :Unit = {
        val map: Map[String, String] = Map("es.nodes"->"testserver1:9200", /*"es.port"->"9200", */"es.index" -> "mb_test_100", "es.type" -> "my_table")

        val esSchema = new EsCatalystSchema(map)
        val ver = esSchema.getVersion
        val tbs = esSchema.getTableNames
        val funs = esSchema.getFunctionNames
        val pair = esSchema.getTableNames2

        println(s"${ver}")
        println(s"${funs}")

        pair.foreach{name =>
            val meta = esSchema.getTableMetaData(s"${name._1}/${name._2}")
            val ts = meta.getTableSchema
            val stat = meta.getTableStats
            println(s"[${name._1}, ${name._2}] ts=$ts, stat=$stat")
        }

        esSchema.finalize()
    }
}
