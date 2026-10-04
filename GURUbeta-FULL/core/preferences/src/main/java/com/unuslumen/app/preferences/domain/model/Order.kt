// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.preferences.domain.model

sealed class OrderType {
    data object ASC : OrderType()
    data object DESC : OrderType()
}
sealed class Order(val orderType: OrderType) {
    abstract fun copyOrder(orderType: OrderType): Order

    data class Alphabetical(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }

    data class DateCreated(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }

    data class DateModified(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }

    data class Priority(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }

    data class DueDate(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }

    data class Done(val type: OrderType = OrderType.ASC) : Order(type) {
        override fun copyOrder(orderType: OrderType): Order {
            return this.copy(type = orderType)
        }
    }
}

fun Int.toOrder(): Order {
    return when(this){
        0 -> Order.Alphabetical(OrderType.ASC)
        1 -> Order.DateCreated(OrderType.ASC)
        2 -> Order.DateModified(OrderType.ASC)
        3 -> Order.Priority(OrderType.ASC)
        8 -> Order.DueDate(OrderType.ASC)
        10 -> Order.Done(OrderType.ASC)
        4 -> Order.Alphabetical(OrderType.DESC)
        5 -> Order.DateCreated(OrderType.DESC)
        6 -> Order.DateModified(OrderType.DESC)
        7 -> Order.Priority(OrderType.DESC)
        9 -> Order.DueDate(OrderType.DESC)
        11 -> Order.Done(OrderType.DESC)
        else -> Order.Alphabetical(OrderType.ASC)
    }
}
fun Order.toInt(): Int {
    return when (this.orderType) {
        is OrderType.ASC -> {
            when (this) {
                is Order.Alphabetical -> 0
                is Order.DateCreated -> 1
                is Order.DateModified -> 2
                is Order.Priority -> 3
                is Order.DueDate -> 8
                is Order.Done -> 10
            }
        }
        is OrderType.DESC -> {
            when (this) {
                is Order.Alphabetical -> 4
                is Order.DateCreated -> 5
                is Order.DateModified -> 6
                is Order.Priority -> 7
                is Order.DueDate -> 9
                is Order.Done -> 11
            }
        }
    }
}