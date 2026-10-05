package shop.cart;

/** O carrinho já tem {@value Cart#MAX_LINES} linhas diferentes. */
public class CartLimitException extends RuntimeException {
    public CartLimitException() {
        super("o carrinho já tem " + Cart.MAX_LINES + " linhas");
    }
}
