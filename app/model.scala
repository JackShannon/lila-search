package lila.search

import scala.collection.JavaConverters._
import com.sksamuel.elastic4s.RichSearchResponse
import play.api.libs.json._
import org.elasticsearch.search.aggregations.bucket.terms.{ Terms }

case class Index(name: String, typeName: String) {
  override def toString = s"$name/$typeName"
}
object Index {
  def apply(name: String): Index = Index(name, name)
}

case class Id(value: String)

case class StringQuery(value: String)
case class From(value: Int)
case class Size(value: Int)

case class SearchResponse(hitIds: List[String])

object SearchResponse {

  def apply(res: RichSearchResponse): SearchResponse =
    SearchResponse(res.hits.toList map (_.id))
}

case class CountResponse(count: Int)

object CountResponse {

  def apply(res: RichSearchResponse): CountResponse =
    CountResponse(res.totalHits.toInt)
}

case class ExplorerResponse(moves: JsValue)

object ExplorerResponse {

  def apply(res: RichSearchResponse): ExplorerResponse = {

  	val newNextMoves = scala.collection.mutable.ArrayBuffer[JsValue]()

  	val nextMoves = res.aggregations.get("nextmoves").asInstanceOf[Terms] 
    nextMoves.getBuckets.asScala foreach (move => {
        println(move.getKey)
        println(move.getDocCount)

        val nextMove = scala.collection.mutable.HashMap[String,JsValue]()
        nextMove("name") = Json.toJson(move.getKey.toString)
        nextMove("total") = Json.toJson(move.getDocCount)

        val winners = move.getAggregations.get("winners").asInstanceOf[Terms]
        winners.getBuckets.asScala foreach (result => {
          println(result.getKey)
          println(result.getDocCount)

          nextMove(result.getKey.toString) = Json.toJson(result.getDocCount)
        })
        println(Json.toJson(nextMove.toMap))
        newNextMoves += Json.toJson(nextMove.toMap)
    })

    newNextMoves.foreach (println)
    println(newNextMoves.length)

  	ExplorerResponse(Json.toJson(newNextMoves.toList))
  } 
}