package edu.gemini.seqexec.server

import edu.gemini.seqexec.model.dhs.ObsId
import edu.gemini.seqexec.server.DhsClient._

/**
  * Created by jluhrs on 1/31/17.
  */
trait Header {
  def sendBefore(id: ObsId, inst: String): SeqAction[Unit]
  def sendAfter(id: ObsId, inst: String): SeqAction[Unit]
}

object Header {
  def buildKeyword[A](get: SeqAction[A], name: String, f: (String, A) => DhsClient.Keyword[A]): KeywordBag => SeqAction[KeywordBag] = k =>
      get.map(x => k.add(f(name, x)))
  def buildInt8(get: SeqAction[Byte], name: String ): KeywordBag => SeqAction[KeywordBag]       = buildKeyword(get, name, Int8Keyword)
  def buildInt16(get: SeqAction[Short], name: String ): KeywordBag => SeqAction[KeywordBag]     = buildKeyword(get, name, Int16Keyword)
  def buildInt32(get: SeqAction[Int], name: String ): KeywordBag => SeqAction[KeywordBag]       = buildKeyword(get, name, Int32Keyword)
  def buildFloat(get: SeqAction[Float], name: String ): KeywordBag => SeqAction[KeywordBag]     = buildKeyword(get, name, FloatKeyword)
  def buildDouble(get: SeqAction[Double], name: String ): KeywordBag => SeqAction[KeywordBag]   = buildKeyword(get, name, DoubleKeyword)
  def buildBoolean(get: SeqAction[Boolean], name: String ): KeywordBag => SeqAction[KeywordBag] = buildKeyword(get, name, BooleanKeyword)
  def buildString(get: SeqAction[String], name: String ): KeywordBag => SeqAction[KeywordBag]   = buildKeyword(get, name, StringKeyword)

  private def bundleKeywords(inst: String, ks: Seq[KeywordBag => SeqAction[KeywordBag]]): SeqAction[KeywordBag] = {
    val z = SeqAction(KeywordBag(StringKeyword("instrument", inst)))
    ks.foldRight(z) { case (a,b) => b.flatMap(a) }
  }

  def sendKeywords(id: ObsId, inst: String, hs: DhsClient, b: Seq[KeywordBag => SeqAction[KeywordBag]]): SeqAction[Unit] = for {
    bag <- bundleKeywords(inst, b)
    _   <- hs.setKeywords(id, bag, false)
  } yield ()

}