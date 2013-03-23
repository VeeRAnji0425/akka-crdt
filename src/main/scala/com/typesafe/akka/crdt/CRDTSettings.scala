/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package com.typesafe.akka.crdt

import com.typesafe.config.Config

class CRDTSettings(val config: Config, val systemName: String) {
  import config._

  final val FeatureX: Double = {
    val x = getDouble("akka.crdt.feature-x")
    require(x > 0.0, "feature-x must be > 0")
    x
  }
}