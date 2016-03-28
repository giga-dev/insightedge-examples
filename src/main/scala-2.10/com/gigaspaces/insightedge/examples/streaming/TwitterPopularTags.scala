package com.gigaspaces.insightedge.examples.streaming

import java.util

import com.gigaspaces.spark.context.GigaSpacesConfig
import com.gigaspaces.spark.implicits._
import com.gigaspaces.spark.streaming.implicits._
import org.apache.spark.SparkConf
import org.apache.spark.streaming.twitter._
import org.apache.spark.streaming.{Seconds, StreamingContext}

import scala.collection.JavaConverters._


/**
  * A modified version of Spark's example that saves tags (all and popular) to Data Grid.
  *
  * @author Oleksiy_Dyagilev
  */
object TwitterPopularTags {

  def main(args: Array[String]) {
    if (args.length < 7) {
      System.err.println("Usage: TwitterPopularTags <spark master url> <consumer key> <consumer secret> " +
        "<access token> <access token secret> <space name> <space locator>")
      System.exit(1)
    }

    val Array(masterUrl, consumerKey, consumerSecret, accessToken, accessTokenSecret, spaceName, spaceLocator) = args.take(7)

    // Set the system properties so that Twitter4j library used by twitter stream
    // can use them to generate OAuth credentials
    System.setProperty("twitter4j.oauth.consumerKey", consumerKey)
    System.setProperty("twitter4j.oauth.consumerSecret", consumerSecret)
    System.setProperty("twitter4j.oauth.accessToken", accessToken)
    System.setProperty("twitter4j.oauth.accessTokenSecret", accessTokenSecret)

    val gsConfig = GigaSpacesConfig(spaceName, None, Some(spaceLocator))
    val sparkConf = new SparkConf().setAppName("TwitterPopularTags").setMaster(masterUrl).setGigaSpaceConfig(gsConfig)

    val ssc = new StreamingContext(sparkConf, Seconds(2))

    val sc = ssc.sparkContext
    val stream = TwitterUtils.createStream(ssc, None)

    val hashTagStrings = stream.flatMap(status => status.getText.split(" ").filter(_.startsWith("#")))
    val hashTags = hashTagStrings.map(tag => new HashTag(tag))

    // saving DStream to Data Grid
    hashTags.saveToGrid()

    val topCounts = hashTagStrings
      .map((_, 1))
      .reduceByKeyAndWindow(_ + _, Seconds(10))
      .map { case (topic, count) => (count, topic) }
      .transform(_.sortByKey(ascending = false))

    topCounts.foreachRDD { rdd =>
      val topList = rdd.take(10)
      val topTags = new TopTags(new util.HashMap(topList.toMap.asJava))
      // saving from driver to Data Grid
      sc.saveToGrid(topTags)
    }

    ssc.start()
    ssc.awaitTermination()
  }
}
