@param java.util.Map<String, String> p

<li class="${"product-card"}"><a href="${io.suko.generated.SukoSafe.url("/p/" + (p.get("id")))}"><img src="${io.suko.generated.SukoSafe.url(p.get("imageUrl"))}" alt="${p.get("name")}" width="${"320"}" height="${"200"}"/><h2>${p.get("name")}</h2><span class="${"price"}">${p.get("price")}</span></a></li>