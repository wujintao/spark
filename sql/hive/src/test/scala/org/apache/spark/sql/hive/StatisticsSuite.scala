/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive

import org.scalatest.BeforeAndAfterAll

import scala.reflect.ClassTag

import org.apache.spark.sql.{SQLConf, QueryTest}
import org.apache.spark.sql.execution.joins._
import org.apache.spark.sql.hive.test.TestHive
import org.apache.spark.sql.hive.test.TestHive._
import org.apache.spark.sql.hive.execution._

class StatisticsSuite extends QueryTest with BeforeAndAfterAll {
  TestHive.reset()
  TestHive.cacheTables = false

  test("parse analyze commands") {
    def assertAnalyzeCommand(analyzeCommand: String, c: Class[_]) {
      val parsed = HiveQl.parseSql(analyzeCommand)
      val operators = parsed.collect {
        case a: AnalyzeTable => a
        case o => o
      }

      assert(operators.size === 1)
      if (operators(0).getClass() != c) {
        fail(
          s"""$analyzeCommand expected command: $c, but got ${operators(0)}
             |parsed command:
             |$parsed
           """.stripMargin)
      }
    }

    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 COMPUTE STATISTICS",
      classOf[HiveNativeCommand])
    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 PARTITION(ds='2008-04-09', hr=11) COMPUTE STATISTICS",
      classOf[HiveNativeCommand])
    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 PARTITION(ds='2008-04-09', hr=11) COMPUTE STATISTICS noscan",
      classOf[HiveNativeCommand])
    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 PARTITION(ds, hr) COMPUTE STATISTICS",
      classOf[HiveNativeCommand])
    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 PARTITION(ds, hr) COMPUTE STATISTICS noscan",
      classOf[HiveNativeCommand])

    assertAnalyzeCommand(
      "ANALYZE TABLE Table1 COMPUTE STATISTICS nOscAn",
      classOf[AnalyzeTable])
  }

  test("analyze MetastoreRelations") {
    def queryTotalSize(tableName: String): BigInt =
      catalog.lookupRelation(Seq(tableName)).statistics.sizeInBytes

    // Non-partitioned table
    sql("CREATE TABLE analyzeTable (key STRING, value STRING)").collect()
    sql("INSERT INTO TABLE analyzeTable SELECT * FROM src").collect()
    sql("INSERT INTO TABLE analyzeTable SELECT * FROM src").collect()

    // TODO: How does it works? needs to add it back for other hive version.
    if (HiveShim.version =="0.12.0") {
      assert(queryTotalSize("analyzeTable") === conf.defaultSizeInBytes)
    }
    sql("ANALYZE TABLE analyzeTable COMPUTE STATISTICS noscan")

    assert(queryTotalSize("analyzeTable") === BigInt(11624))

    sql("DROP TABLE analyzeTable").collect()

    // Partitioned table
    sql(
      """
        |CREATE TABLE analyzeTable_part (key STRING, value STRING) PARTITIONED BY (ds STRING)
      """.stripMargin).collect()
    sql(
      """
        |INSERT INTO TABLE analyzeTable_part PARTITION (ds='2010-01-01')
        |SELECT * FROM src
      """.stripMargin).collect()
    sql(
      """
        |INSERT INTO TABLE analyzeTable_part PARTITION (ds='2010-01-02')
        |SELECT * FROM src
      """.stripMargin).collect()
    sql(
      """
        |INSERT INTO TABLE analyzeTable_part PARTITION (ds='2010-01-03')
        |SELECT * FROM src
      """.stripMargin).collect()

    assert(queryTotalSize("analyzeTable_part") === conf.defaultSizeInBytes)

    sql("ANALYZE TABLE analyzeTable_part COMPUTE STATISTICS noscan")

    assert(queryTotalSize("analyzeTable_part") === BigInt(17436))

    sql("DROP TABLE analyzeTable_part").collect()

    // Try to analyze a temp table
    sql("""SELECT * FROM src""").registerTempTable("tempTable")
    intercept[NotImplementedError] {
      analyze("tempTable")
    }
    catalog.unregisterTable(Seq("tempTable"))
  }

  test("estimates the size of a test MetastoreRelation") {
    val rdd = sql("""SELECT * FROM src""")
    val sizes = rdd.queryExecution.analyzed.collect { case mr: MetastoreRelation =>
      mr.statistics.sizeInBytes
    }
    assert(sizes.size === 1, s"Size wrong for:\n ${rdd.queryExecution}")
    assert(sizes(0).equals(BigInt(5812)),
      s"expected exact size 5812 for test table 'src', got: ${sizes(0)}")
  }

  test("auto converts to broadcast hash join, by size estimate of a relation") {
    def mkTest(
        before: () => Unit,
        after: () => Unit,
        query: String,
        expectedAnswer: Seq[Any],
        ct: ClassTag[_]) = {
      before()

      var rdd = sql(query)

      // Assert src has a size smaller than the threshold.
      val sizes = rdd.queryExecution.analyzed.collect {
        case r if ct.runtimeClass.isAssignableFrom(r.getClass) => r.statistics.sizeInBytes
      }
      assert(sizes.size === 2 && sizes(0) <= conf.autoBroadcastJoinThreshold
        && sizes(1) <= conf.autoBroadcastJoinThreshold,
        s"query should contain two relations, each of which has size smaller than autoConvertSize")

      // Using `sparkPlan` because for relevant patterns in HashJoin to be
      // matched, other strategies need to be applied.
      var bhj = rdd.queryExecution.sparkPlan.collect { case j: BroadcastHashJoin => j }
      assert(bhj.size === 1,
        s"actual query plans do not contain broadcast join: ${rdd.queryExecution}")

      checkAnswer(rdd, expectedAnswer) // check correctness of output

      TestHive.conf.settings.synchronized {
        val tmp = conf.autoBroadcastJoinThreshold

        sql(s"""SET ${SQLConf.AUTO_BROADCASTJOIN_THRESHOLD}=-1""")
        rdd = sql(query)
        bhj = rdd.queryExecution.sparkPlan.collect { case j: BroadcastHashJoin => j }
        assert(bhj.isEmpty, "BroadcastHashJoin still planned even though it is switched off")

        val shj = rdd.queryExecution.sparkPlan.collect { case j: ShuffledHashJoin => j }
        assert(shj.size === 1,
          "ShuffledHashJoin should be planned when BroadcastHashJoin is turned off")

        sql(s"""SET ${SQLConf.AUTO_BROADCASTJOIN_THRESHOLD}=$tmp""")
      }

      after()
    }

    /** Tests for MetastoreRelation */
    val metastoreQuery = """SELECT * FROM src a JOIN src b ON a.key = 238 AND a.key = b.key"""
    val metastoreAnswer = Seq.fill(4)((238, "val_238", 238, "val_238"))
    mkTest(
      () => (),
      () => (),
      metastoreQuery,
      metastoreAnswer,
      implicitly[ClassTag[MetastoreRelation]]
    )
  }

  test("auto converts to broadcast left semi join, by size estimate of a relation") {
    val leftSemiJoinQuery =
      """SELECT * FROM src a
        |left semi JOIN src b ON a.key=86 and a.key = b.key""".stripMargin
    val answer = (86, "val_86") :: Nil

    var rdd = sql(leftSemiJoinQuery)

    // Assert src has a size smaller than the threshold.
    val sizes = rdd.queryExecution.analyzed.collect {
      case r if implicitly[ClassTag[MetastoreRelation]].runtimeClass
        .isAssignableFrom(r.getClass) =>
        r.statistics.sizeInBytes
    }
    assert(sizes.size === 2 && sizes(1) <= conf.autoBroadcastJoinThreshold
      && sizes(0) <= conf.autoBroadcastJoinThreshold,
      s"query should contain two relations, each of which has size smaller than autoConvertSize")

    // Using `sparkPlan` because for relevant patterns in HashJoin to be
    // matched, other strategies need to be applied.
    var bhj = rdd.queryExecution.sparkPlan.collect {
      case j: BroadcastLeftSemiJoinHash => j
    }
    assert(bhj.size === 1,
      s"actual query plans do not contain broadcast join: ${rdd.queryExecution}")

    checkAnswer(rdd, answer) // check correctness of output

    TestHive.conf.settings.synchronized {
      val tmp = conf.autoBroadcastJoinThreshold

      sql(s"SET ${SQLConf.AUTO_BROADCASTJOIN_THRESHOLD}=-1")
      rdd = sql(leftSemiJoinQuery)
      bhj = rdd.queryExecution.sparkPlan.collect {
        case j: BroadcastLeftSemiJoinHash => j
      }
      assert(bhj.isEmpty, "BroadcastHashJoin still planned even though it is switched off")

      val shj = rdd.queryExecution.sparkPlan.collect {
        case j: LeftSemiJoinHash => j
      }
      assert(shj.size === 1,
        "LeftSemiJoinHash should be planned when BroadcastHashJoin is turned off")

      sql(s"SET ${SQLConf.AUTO_BROADCASTJOIN_THRESHOLD}=$tmp")
    }

  }
}
