package com.adendamedia

class EventCounterNumber(num: Int)(implicit val max_val: Int) {
  def nextEventNumber: Int = if (num + 1 > max_val) 0 else num + 1
}

