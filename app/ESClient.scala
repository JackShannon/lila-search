package lila.search

import scala.concurrent.Future
import scala.collection.JavaConverters._
import scala.util.{Success, Failure}

import org.elasticsearch.search.aggregations.bucket.terms.{ Terms }

import com.sksamuel.elastic4s.ElasticDsl.{ RichFuture => _, _ }
import com.sksamuel.elastic4s.mappings.{ TypedFieldDefinition }
import com.sksamuel.elastic4s.analyzers.{ AnalyzerDefinition, CustomAnalyzerDefinition }
import com.sksamuel.elastic4s.{ ElasticDsl, ElasticClient }
import play.api.libs.json._

final class ESClient(client: ElasticClient) {

  private var writeable = true

  private def Write[A](f: => Fu[A]): Funit =
    if (writeable) f.void
    else funit

  def search(index: Index, query: Query, from: From, size: Size) = client execute {
    query.searchDef(from, size)(index)
  } map SearchResponse.apply

  def count(index: Index, query: Query) = client execute {
    query.countDef(index)
  } map CountResponse.apply

  def store(index: Index, id: Id, obj: JsObject) = Write {
    client execute {
      ElasticDsl.index into index.toString source Json.stringify(obj) id id.value
    }
  }

  def storeBulk(index: Index, objs: JsObject) =
    if (objs.fields.isEmpty) funit
    else client execute {
      ElasticDsl.bulk {
        objs.fields.collect {
          case (id, JsString(doc)) =>
            ElasticDsl.index into index.toString source doc id id
        }
      }
    }

  def deleteById(index: Index, id: Id) = Write {
    client execute {
      ElasticDsl.delete id id.value from index.toString
    }
  }

  def deleteByIds(index: Index, ids: List[Id]) = Write {
    client execute {
      ElasticDsl.bulk {
        ids.map { id =>
          ElasticDsl.delete id id.value from index.toString
        }
      }
    }
  }

  def putMapping(index: Index, fields: Seq[TypedFieldDefinition]) =
    client.execute {
      ElasticDsl.create index index.name mappings (
        mapping(index.name) fields fields
      ) shards 1 replicas 0 refreshInterval "30s"
    }

  def putMappingWithAnalysis(index: Index, fields: Seq[TypedFieldDefinition], analysisDefinitions: Seq[AnalyzerDefinition]) =
    client.execute {
      ElasticDsl.create index index.name mappings (
        mapping(index.name) fields fields
      ) shards 1 replicas 0 refreshInterval "30s" analysis (
        analysisDefinitions
      )
    }

  def aliasTo(tempIndex: Index, mainIndex: Index) = {
    writeable = false
    deleteIndex(mainIndex) >> client.execute {
      add alias mainIndex.name on tempIndex.name
    } >> Future {
      writeable = true
    } >> client.execute {
      ElasticDsl forceMerge mainIndex.name
    }
  }



  //       """{ "filtered": { "query": { "match_phrase_prefix": { "m" : """""+ ((obj \ "moves").as[String]) +""""" } } } }"""


  def gameExplorer(obj: JsObject) = {
    val moves = (obj \ "moves").as[String]
    var nMoves = 0
    if (moves.length > 0) {
      nMoves = moves.split(" ").length
    }

    client execute {
      ElasticDsl.search in "game"->"game" query {
        queryStringQuery(s"m:$moves*") analyzer("moves_analyzer")
      } aggs (
        aggregation terms "nextmoves" field "m" aggs (
          aggregation terms "winners" field "c"
        ) script s"_value.split(' ')[$nMoves]" size 10 
      ) size 0
    } map ExplorerResponse.apply
  }

  private def deleteIndex(index: Index) =
    client.execute {
      ElasticDsl.delete index index.name
    }.recover {
      case _: Exception =>
    }
}
