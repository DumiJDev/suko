@param java.util.List<java.util.Map<String, String>> products

<ul class="${"product-grid"}">@for(java.util.Map<String, String> p : products)
@template.shop.ProductCard(p = p)

@endfor
</ul>