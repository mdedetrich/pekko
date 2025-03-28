/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.cluster

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.apache.pekko
import pekko.cluster.MemberStatus.WeaklyUp
import pekko.remote.testkit.MultiNodeConfig
import pekko.remote.transport.ThrottlerTransportAdapter.Direction
import pekko.testkit._

object MemberWeaklyUpSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
        pekko.remote.retry-gate-closed-for = 3 s
        pekko.cluster.allow-weakly-up-members = 3 s
        """)).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

class MemberWeaklyUpMultiJvmNode1 extends MemberWeaklyUpSpec
class MemberWeaklyUpMultiJvmNode2 extends MemberWeaklyUpSpec
class MemberWeaklyUpMultiJvmNode3 extends MemberWeaklyUpSpec
class MemberWeaklyUpMultiJvmNode4 extends MemberWeaklyUpSpec
class MemberWeaklyUpMultiJvmNode5 extends MemberWeaklyUpSpec

abstract class MemberWeaklyUpSpec extends MultiNodeClusterSpec(MemberWeaklyUpSpec) {

  import MemberWeaklyUpSpec._

  muteMarkingAsUnreachable()

  val side1 = Vector(first, second)
  val side2 = Vector(third, fourth, fifth)

  "A cluster of 3 members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(first, third, fourth)

      enterBarrier("after-1")
    }

    "detect network partition and mark nodes on other side as unreachable" taggedAs LongRunningTest in within(
      20.seconds) {
      runOn(first) {
        // split the cluster in two parts (first, second) / (third, fourth, fifth)
        for (role1 <- side1; role2 <- side2) {
          testConductor.blackhole(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("after-split")

      runOn(first) {
        awaitAssert(clusterView.unreachableMembers.map(_.address) should be(Set(address(third), address(fourth))))
      }

      runOn(third, fourth) {
        awaitAssert(clusterView.unreachableMembers.map(_.address) should be(Set(address(first))))
      }

      enterBarrier("after-2")
    }

    "accept joining on each side and set status to WeaklyUp" taggedAs LongRunningTest in within(20.seconds) {
      runOn(second) {
        Cluster(system).join(first)
      }
      runOn(fifth) {
        Cluster(system).join(fourth)
      }
      enterBarrier("joined")

      runOn(side1: _*) {
        awaitAssert {
          clusterView.members.size should be(4)
          clusterView.members.exists { m =>
            m.address == address(second) && m.status == WeaklyUp
          } should be(true)
        }
      }

      runOn(side2: _*) {
        awaitAssert {
          clusterView.members.size should be(4)
          clusterView.members.exists { m =>
            m.address == address(fifth) && m.status == WeaklyUp
          } should be(true)
        }
      }

      enterBarrier("after-3")
    }

    "change status to Up after healed network partition" taggedAs LongRunningTest in within(20.seconds) {
      runOn(first) {
        for (role1 <- side1; role2 <- side2) {
          testConductor.passThrough(role1, role2, Direction.Both).await
        }
      }
      enterBarrier("after-passThrough")

      awaitAllReachable()
      awaitMembersUp(5)

      enterBarrier("after-4")
    }

  }

}
