package com.dima.fooddelivery.order;

import java.math.BigDecimal;

/** Позиция заказа: название и цена блюда на момент оформления. */
public record OrderItem(
        Long menuItemId,
        String menuItemName,
        Integer quantity,
        BigDecimal price
) {
}
