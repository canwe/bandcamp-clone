package controllers


import actions._

import play.api.mvc._
import models.SiteDB._
import org.squeryl.PrimitiveTypeMode._
import models._
import utils.Utils



import play.api.libs.json._
import play.api.libs.json.Json._
import com.codahale.jerkson.Json._
import play.api.cache.Cache
import play.api.libs.json.JsArray
import play.api.libs.json.JsString
import play.api.libs.json.JsObject

import org.squeryl.dsl.ast.{FunctionNode, BinaryOperatorNodeLogicalBoolean}
import org.squeryl.dsl.StringExpression
import org.squeryl.internals.{StatementWriter, OutMapper}

/**
 * Created by IntelliJ IDEA.
 * User: Keyston
 * Date: 9/17/12
 * Time: 12:21 PM
 */
object Api extends Controller with WithCommon{

  import json.Writes._


  def withGenre(tags: Seq[String]) = from(genres)(g => where(g.tag in tags) select (g.id)).toSeq


  def prepQuery(query: String) = "%" + query + "%"

  def buildWhere(t: TrackWithTags, a: Artist, query: Option[String], genres: Option[Seq[String]]) = {
    val expr = Seq(
      if (query.nonEmpty) Some((a.name like query.get)
        or (t.name like query.get) or (t.tags like query.get))
      else None,

      if (genres.nonEmpty) Some(t.genreID in withGenre(genres.get)) else None,
      Some(t.active === true)


    ).filter(_ != None).map(_.get)
    if (expr.isEmpty) 1 === 1 else expr.reduceRight((ex1, ex2) => new BinaryOperatorNodeLogicalBoolean(ex1, ex2, "and"))
  }

  def find(query: Option[String], genres: Option[Seq[String]]) = join(tracksWithTags, albumTracks.leftOuter, albums.leftOuter, artists,
    ratings.leftOuter)((t, at, ab, a, r) =>

    where(buildWhere(t, a, query, genres))
      select(t, a, ab, r)

      on(

      at.map(_.trackID) === t.id,
      at.map(_.albumID) === ab.map(_.id),

      t.artistID === a.id,
      r.map(_.trackID) === t.id


      )


  )


  def writeResults(kind: String, results: JsArray) = JsObject(Seq(("kind", JsString(kind)), ("results", results))).toString

  def withPoints(implicit request: Request[AnyContent]): Option[Either[String, Double]] = {
    request.body.asFormUrlEncoded.get("points").headOption.map {
      i =>
        try {
          Right(java.lang.Double.parseDouble(i))
        } catch {
          case e: NumberFormatException => Left("Cannot parse parameter points as Double: " + e.getMessage)
        }
    }
  }

  def withRate(artistID: Long, slug: String, points: Double): Unit = Track.bySlug(artistID, slug).map {
    t =>
      try {
        Rating(t.id, 1, points).save
      } catch {
        case e: RuntimeException =>
          if (e.getMessage.contains("Duplicate")) {
            update(ratings)(r =>
              where(r.trackID === t.id)
                set(r.votes := r.votes plus 1,
                r.points := r.points plus points)
            )
          }
      }
  }

  def rate(slug: String) = WithArtist {
    artist => implicit request =>
      val o = withPoints.map {
        wp => wp match {
          case Right(points) => withRate(artist.id, slug, points)
          true
          case _ => false
        }
      }.getOrElse(false)

      Ok(JsObject(Seq(("kind", JsString("rate")), ("ok", JsBoolean(o)))).toString)


  }

  def artist(domain: String) = TransAction {
    Action {
      val o = Artist.findByDomain(domain).map {
        a => toJson(Full(a)).toString
      }.getOrElse("")


      Ok(o)
    }
  }

  def fetch(tags: Option[String], term: Option[String], page: Int, amount: Int) = TransAction {

    Action {


      val rs = find(term.filter(_.nonEmpty).map(prepQuery(_)), tags.filter(_.nonEmpty).map(_.split(",").toSeq))
        .page((page - 1) * amount, amount).map(toJson(_)).toSeq
      /*val rs = query.map(withQuery(_, tags))
        .getOrElse(withTags(tags))
        ..map(toJson(_)).toSeq */


      Ok(writeResults("tracks", JsArray(rs)))
    }

  }
}
