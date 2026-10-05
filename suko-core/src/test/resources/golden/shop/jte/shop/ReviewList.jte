@param java.util.List<java.util.Map<String, String>> reviews

@if(reviews.isEmpty())
<p class="${"empty"}">Ainda não há avaliações.</p>
@else
<ul class="${"reviews"}">@for(java.util.Map<String, String> r : reviews)
<li class="${"review"}"><strong class="${"author"}">${r.get("author")}</strong><span class="${"rating"}">${r.get("rating")} em 5</span><p class="${"body"}">${r.get("body")}</p>@if(!r.get("website").isEmpty())
<a class="${"website"}" href="${io.suko.generated.SukoSafe.url(r.get("website"))}" rel="${"nofollow ugc"}">site do autor</a>
@endif
</li>
@endfor
</ul>
@endif
