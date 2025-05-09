/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster.metrics.sample

import org.apache.pekko
import pekko.actor.Props
import pekko.cluster.Cluster
import pekko.cluster.ClusterEvent.{ CurrentClusterState, MemberUp }

import scala.concurrent.duration._

//#MultiNodeConfig
import org.apache.pekko.remote.testkit.MultiNodeConfig
import com.typesafe.config.ConfigFactory

object StatsSampleSpecConfig extends MultiNodeConfig {
  // register the named roles (nodes) of the test
  val first = role("first")
  val second = role("second")
  val third = role("third")

  def nodeList = Seq(first, second, third)

  // Extract individual sigar library for every node.
  nodeList.foreach { role =>
    nodeConfig(role) {
      ConfigFactory.parseString(s"""
      # Enable metrics extension in pekko-cluster-metrics.
      pekko.extensions=["org.apache.pekko.cluster.metrics.ClusterMetricsExtension"]
      # Sigar native library extract location during tests.
      pekko.cluster.metrics.native-library-extract-folder=target/native/${role.name}
      """)
    }
  }

  // this configuration will be used for all nodes
  // note that no fixed host names and ports are used
  commonConfig(ConfigFactory.parseString("""
    pekko.actor.provider = cluster
    pekko.remote.classic.log-remote-lifecycle-events = off
    pekko.cluster.roles = [compute]
    #//#router-lookup-config
    pekko.actor.deployment {
      /statsService/workerRouter {
          router = consistent-hashing-group
          routees.paths = ["/user/statsWorker"]
          cluster {
            enabled = on
            allow-local-routees = on
            use-roles = ["compute"]
          }
        }
    }
    #//#router-lookup-config
    """))

}
//#MultiNodeConfig

//#concrete-tests
// need one concrete test class per node
class StatsSampleSpecMultiJvmNode1 extends StatsSampleSpec
class StatsSampleSpecMultiJvmNode2 extends StatsSampleSpec
class StatsSampleSpecMultiJvmNode3 extends StatsSampleSpec
//#concrete-tests

//#abstract-test
import org.apache.pekko
import pekko.remote.testkit.MultiNodeSpec
import pekko.testkit.ImplicitSender
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

abstract class StatsSampleSpec
    extends MultiNodeSpec(StatsSampleSpecConfig)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with ImplicitSender {

  import StatsSampleSpecConfig._

  override def initialParticipants = roles.size

  override def beforeAll() = multiNodeSpecBeforeAll()

  override def afterAll() = multiNodeSpecAfterAll()

  // #abstract-test

  "The stats sample" must {

    // #startup-cluster
    "illustrate how to startup cluster" in within(15.seconds) {
      Cluster(system).subscribe(testActor, classOf[MemberUp])
      expectMsgClass(classOf[CurrentClusterState])

      // #addresses
      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address
      // #addresses

      // #join
      Cluster(system).join(firstAddress)
      // #join

      system.actorOf(Props[StatsWorker](), "statsWorker")
      system.actorOf(Props[StatsService](), "statsService")

      receiveN(3).collect { case MemberUp(m) => m.address }.toSet should be(
        Set(firstAddress, secondAddress, thirdAddress))

      Cluster(system).unsubscribe(testActor)

      testConductor.enter("all-up")
    }
    // #startup-cluster

    // #test-statsService
    "show usage of the statsService from one node" in within(15.seconds) {
      runOn(second) {
        assertServiceOk()
      }

      testConductor.enter("done-2")
    }

    def assertServiceOk(): Unit = {
      val service = system.actorSelection(node(third) / "user" / "statsService")
      // eventually the service should be ok,
      // first attempts might fail because worker actors not started yet
      awaitAssert {
        service ! StatsJob("this is the text that will be analyzed")
        expectMsgType[StatsResult](1.second).meanWordLength should be(3.875 +- 0.001)
      }

    }
    // #test-statsService

    "show usage of the statsService from all nodes" in within(15.seconds) {
      assertServiceOk()
      testConductor.enter("done-3")
    }

  }

}
