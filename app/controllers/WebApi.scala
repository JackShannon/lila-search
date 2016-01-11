package controllers

import akka.actor._
import javax.inject._
import lila.search._
import play.api._
import play.api.http.ContentTypes._
import play.api.libs.json._
import play.api.mvc._

@Singleton
class WebApi @Inject() (
    protected val system: ActorSystem,
    protected val lifecycle: play.api.inject.ApplicationLifecycle) extends Controller with WithES {

  def store(index: String, id: String) = JsObjectBody { obj =>
    client.store(Index(index), Id(id), obj) inject Ok(s"inserted $index/$id")
  }

  def deleteById(index: String, id: String) = Action.async {
    client.deleteById(Index(index), Id(id)) inject Ok(s"deleted $index/$id")
  }

  def deleteByIds(index: String) = JsObjectBody { obj =>
    (obj \ "ids").asOpt[List[String]] match {
      case Some(ids) =>
        client.deleteByIds(Index(index), ids map Id) inject Ok(s"deleted ${ids.size} ids from $index")
      case _ => fuccess(BadRequest(obj))
    }
  }

  def search(index: String, from: Int, size: Int) = JsObjectBody { obj =>
    Which.query(Index(index))(obj) match {
      case None => fuccess(NotFound(s"Can't parse query for $index"))
      case Some(query) => client.search(Index(index), query, From(from), Size(size)) map { res =>
        Ok(res.hitIds mkString ",")
      }
    }
  }

  def count(index: String) = JsObjectBody { obj =>
    Which.query(Index(index))(obj) match {
      case None => fuccess(NotFound(s"Can't parse query for $index"))
      case Some(query) => client.count(Index(index), query) map { res =>
        Ok(res.count.toString)
      }
    }
  }

  def mapping(index: String, typ: String) = Action.async {
    Which mapping Index(index, typ) match {
      case None => fuccess(NotFound(s"No such mapping: $index/$typ"))
      case Some(mapping) =>
        Which analysis Index(index, typ) match {
          case None => client.putMapping(Index(index, typ), mapping) inject Ok(s"put $index/$typ mapping")
          case Some(analysis) => client.putMappingWithAnalysis(Index(index, typ), mapping, analysis) inject Ok(s"put $index/$typ mapping with analysis")
        }
    }
  }

  def storeBulk(index: String, typ: String) = JsObjectBody { objs =>
    Chronometer(s"bulk ${objs.fields.size} $index/$typ") {
      client.storeBulk(Index(index, typ), objs)
    }
    fuccess(Ok(s"bulk inserted $index")) // async!
  }

  def alias(temp: String, main: String) = Action.async {
    client.aliasTo(Index(temp), Index(main)) inject Ok(s"aliased temp:$temp to main:$main")
  }

  def gameExplorer() = JsObjectBody { obj =>
    client.gameExplorer(obj) map { res =>
      Ok(res.moves)
    }
  }

  private def JsObjectBody(f: JsObject => Fu[Result]) =
    Action.async(BodyParsers.parse.json(maxLength = 10 * 1024 * 1024)) { req =>
      req.body.validate[JsObject].fold(
        err => fuccess(BadRequest(err.toString)),
        obj => f(obj) recover {
          case e: Exception =>
            val msg = s"${Json.prettyPrint(obj)}\n\n${e.getMessage}"
            logger warn msg
            BadRequest(msg)
        }
      ) map (_ as TEXT)
    }

  private val logger = play.api.Logger("search")
}
