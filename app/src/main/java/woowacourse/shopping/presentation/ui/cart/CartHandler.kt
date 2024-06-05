package woowacourse.shopping.presentation.ui.cart

import woowacourse.shopping.domain.ProductListItem
import woowacourse.shopping.presentation.ui.QuantityHandler

interface CartHandler : QuantityHandler {
    fun onDeleteClick(product: ProductListItem.ShoppingProductItem)

    fun onCheckBoxClicked(product: ProductListItem.ShoppingProductItem)

    fun onTotalCheckBoxClicked(isChecked: Boolean)

    fun onOrderClicked()
}
