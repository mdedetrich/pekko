/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.actor

import org.apache.pekko.util.Unsafe

object AbstractActorRef {
  private[actor] var cellOffset = 0L
  private[actor] var lookupOffset = 0L

  try {
    cellOffset =
      Unsafe.instance.objectFieldOffset(classOf[RepointableActorRef].getDeclaredField("_cellDoNotCallMeDirectly"))
    lookupOffset =
      Unsafe.instance.objectFieldOffset(classOf[RepointableActorRef].getDeclaredField("_lookupDoNotCallMeDirectly"))
  } catch {
    case t: Throwable =>
      throw new ExceptionInInitializerError(t)
  }

}
