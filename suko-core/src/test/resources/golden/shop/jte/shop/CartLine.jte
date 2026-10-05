@param java.util.Map<String, String> line
@param String csrfName
@param String csrfToken

<li class="${"cart-line"}"><a class="${"name"}" href="${io.suko.generated.SukoSafe.url("/p/" + (line.get("id")))}">${line.get("name")}</a><span class="${"quantity"}">${line.get("quantity")} x ${line.get("unitPrice")}</span><span class="${"line-total"}">${line.get("lineTotal")}</span><form method="${"post"}" action="${"/cart/remove"}"><input type="${"hidden"}" name="${csrfName}" value="${csrfToken}"/><input type="${"hidden"}" name="${"id"}" value="${line.get("id")}"/><button type="${"submit"}">Remover</button></form></li>