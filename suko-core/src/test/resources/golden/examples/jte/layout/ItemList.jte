@param java.util.List<String> items
@param java.util.function.Function<String, gg.jte.Content> row

<ul class="${"item-list"}">@for(String item : items)
<li>${row.apply(item)}</li>
@endfor
</ul>