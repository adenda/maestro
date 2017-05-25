package com.adendamedia.metrics

object RedisSample {

  /**
    * A Redis sample is a tuple. The first value is a list of values from each node. The second value is the total
    * number of nodes.
    */
  type RedisSample = (List[Int], Int)
}
