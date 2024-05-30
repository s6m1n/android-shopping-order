package woowacourse.shopping.presentation.ui.cart

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import woowacourse.shopping.domain.Cart
import woowacourse.shopping.domain.ProductListItem
import woowacourse.shopping.domain.repository.CartRepository
import woowacourse.shopping.domain.repository.OrderRepository
import woowacourse.shopping.domain.repository.ProductRepository
import woowacourse.shopping.domain.repository.RecentRepository
import woowacourse.shopping.presentation.ui.UiState
import woowacourse.shopping.presentation.util.Event

class CartViewModel(
    private val cartRepository: CartRepository,
    private val productRepository: ProductRepository,
    private val recentRepository: RecentRepository,
    private val orderRepository: OrderRepository,
) : ViewModel(), CartHandler {
    private val _error = MutableLiveData<Event<CartError>>()

    val error: LiveData<Event<CartError>> = _error

    private val _orderState = MutableLiveData<OrderState>(OrderState.CartList)

    val orderState: LiveData<OrderState> = _orderState

    private val _orderEvent = MutableLiveData<Event<OrderEvent>>()

    val orderEvent: LiveData<Event<OrderEvent>> = _orderEvent

    private val _recommendedProduct =
        MutableLiveData<List<ProductListItem.ShoppingProductItem>>()

    val recommendedProduct: LiveData<List<ProductListItem.ShoppingProductItem>> get() = _recommendedProduct
    private val _shoppingProducts =
        MutableLiveData<UiState<List<ProductListItem.ShoppingProductItem>>>(UiState.Loading)

    val shoppingProducts: LiveData<UiState<List<ProductListItem.ShoppingProductItem>>> get() = _shoppingProducts

    private val _changedCartProducts = MutableLiveData<List<Cart>>()
    val changedCartProducts: LiveData<List<Cart>> get() = _changedCartProducts

    val isAllCartItemsSelected: LiveData<Boolean> =
        shoppingProducts.switchMap {
            MutableLiveData(
                if (it is UiState.Success && it.data.isNotEmpty()) {
                    it.data.all { it.isChecked }
                } else {
                    false
                },
            )
        }

    private val selectedCartItems: LiveData<List<ProductListItem.ShoppingProductItem>> =
        shoppingProducts.switchMap {
            MutableLiveData(
                if (it is UiState.Success) {
                    it.data.filter { it.isChecked }
                } else {
                    emptyList()
                },
            )
        }

    val totalPrice: LiveData<Long> =
        selectedCartItems.switchMap {
            MutableLiveData(
                it.sumOf { it.price * it.quantity },
            )
        }

    val totalCount: LiveData<Int> =
        selectedCartItems.switchMap {
            MutableLiveData(
                it.sumOf { it.quantity },
            )
        }

    fun setOrderState(state: OrderState) {
        _orderState.value = state
    }

    fun loadAllCartItems(pageSize: Int) {
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({
            cartRepository.load(0, pageSize, onSuccess = { carts, _ ->
                _shoppingProducts.value = UiState.Success(carts.map { it.toShoppingProduct() })
            }, onFailure = { _error.value = Event(CartError.CartItemsNotFound) })
        }, 500)
    }

    override fun onDeleteClick(product: ProductListItem.ShoppingProductItem) {
        cartRepository.deleteExistCartItem(product.cartId, onSuccess = { cartId, newQuantity ->
            val originalChangedCartProducts = changedCartProducts.value ?: emptyList()
            _changedCartProducts.value =
                originalChangedCartProducts.plus(Cart(cartId, product.toProduct(), newQuantity))
            val newShoppingProduct =
                (shoppingProducts.value as UiState.Success<List<ProductListItem.ShoppingProductItem>>).data.filter { it.id != product.id }
            _shoppingProducts.value = UiState.Success(newShoppingProduct)
        }, onFailure = { _error.value = Event(CartError.CartItemNotDeleted) })
    }

    override fun onCheckBoxClicked(product: ProductListItem.ShoppingProductItem) {
        if (shoppingProducts.value is UiState.Success) {
            val cartItems =
                (shoppingProducts.value as UiState.Success<List<ProductListItem.ShoppingProductItem>>).data

            val updatedCartItems =
                cartItems.map {
                    if (it.id == product.id) {
                        it.copy(isChecked = !product.isChecked)
                    } else {
                        it
                    }
                }
            _shoppingProducts.value = UiState.Success(updatedCartItems)
        }
    }

    override fun onTotalCheckBoxClicked(isChecked: Boolean) {
        if (shoppingProducts.value is UiState.Success) {
            val cart =
                shoppingProducts.value as UiState.Success<List<ProductListItem.ShoppingProductItem>>
            _shoppingProducts.value =
                UiState.Success(cart.data.map { it.copy(isChecked = isChecked) })
        }
    }

    override fun onOrderClicked() {
        when (orderState.value) {
            is OrderState.CartList -> {
                if (totalCount.value != 0) {
                    _orderEvent.value = Event(OrderEvent.MoveToRecommend)
                }
            }

            is OrderState.Recommend -> {
                _orderEvent.value = Event(OrderEvent.CompleteOrder)
            }

            null -> throw IllegalAccessError()
        }
    }

    private fun modifyShoppingProductQuantity(
        cartItem: ProductListItem.ShoppingProductItem,
        resultQuantity: Int,
    ) {
        val newShoppingProducts =
            (shoppingProducts.value as UiState.Success<List<ProductListItem.ShoppingProductItem>>).data.map {
                if (it.cartId == cartItem.cartId) {
                    it.copy(quantity = resultQuantity, isChecked = cartItem.isChecked)
                } else {
                    it
                }
            }
        _shoppingProducts.value = UiState.Success(newShoppingProducts)
    }

    fun buildRecommendProducts() {
        recentRepository.loadMostRecent().onSuccess {
            val recentViewedCategory = it?.category ?: "최근 본 아이템 없음"
            productRepository.loadWithCategory(
                recentViewedCategory,
                0,
                20,
                onSuccess = { products ->
                    val existCarts =
                        (shoppingProducts.value as UiState.Success<List<ProductListItem.ShoppingProductItem>>).data.map { it.toProduct() }
                    _recommendedProduct.value =
                        (products - existCarts.toSet()).take(10).map { it.toInitialShoppingItem() }
                },
                onFailure = {},
            )
        }
    }

    override fun onDecreaseQuantity(item: ProductListItem.ShoppingProductItem?) {
        val updatedQuantity = item?.let { it.quantity - 1 } ?: 1
        if (updatedQuantity > 0) {
            item?.let { item ->
                cartRepository.updateDecrementQuantity(
                    item.cartId,
                    item.id,
                    1,
                    item.quantity,
                    onSuccess = { cartId, resultQuantity ->
//                        modifyShoppingProductQuantity(item, resultQuantity)
                        val orderState = orderState.value ?: throw IllegalStateException()
                        handleQuantity(orderState, item, resultQuantity, cartId)
                    },
                    onFailure = {},
                )
            }
        }
    }

    override fun onIncreaseQuantity(item: ProductListItem.ShoppingProductItem?) {
        item?.let { item ->
            cartRepository.updateIncrementQuantity(
                item.cartId,
                item.id,
                1,
                item.quantity,
                onSuccess = { cartId, resultQuantity ->
                    val orderState = orderState.value ?: throw IllegalStateException()
                    handleQuantity(orderState, item, resultQuantity, cartId)
                },
                onFailure = {},
            )
        }
    }

    private fun handleQuantity(
        orderState: OrderState,
        item: ProductListItem.ShoppingProductItem,
        resultQuantity: Int,
        cartId: Long,
    ) {
        when (orderState) {
            OrderState.CartList -> {
                modifyShoppingProductQuantity(item, resultQuantity)
            }

            OrderState.Recommend -> {
                modifyRecentProductQuantity(cartId, item, resultQuantity)
            }
        }
    }

    private fun modifyRecentProductQuantity(
        cartId: Long,
        selectedItem: ProductListItem.ShoppingProductItem,
        resultQuantity: Int,
    ) {
        val recommended = recommendedProduct.value ?: emptyList()
        val updated =
            recommended.map {
                if (it.id == selectedItem.id) {
                    it.copy(cartId = cartId, quantity = resultQuantity)
                } else {
                    it
                }
            }
        _recommendedProduct.value = updated
    }

    fun completeOrder() {
        val cartItemIds = selectedCartItems.value?.map { it.cartId } ?: emptyList()
        if (cartItemIds.isNotEmpty()) {
            orderRepository.completeOrder(cartItemIds, onSuccess = {
                _orderEvent.value = Event(OrderEvent.FinishOrder)
            }, onFailure = {})
        }
    }
}
