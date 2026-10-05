@param String title
@param int cartCount
@param java.util.List<java.util.Map<String, String>> categories
@param java.util.Map<String, String> category
@param java.util.List<java.util.Map<String, String>> products

@template.shop.Layout(title = title, cartCount = cartCount, categories = categories, children = @`<h1>${category.get("name")}</h1>@if(products.isEmpty())
<p class="${"empty"}">Ainda não há produtos nesta categoria.</p>
@else
@template.shop.ProductGrid(products = products)

@endif
`)
