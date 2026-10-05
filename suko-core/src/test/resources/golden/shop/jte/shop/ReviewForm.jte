@param String productId
@param String csrfName
@param String csrfToken
@param java.util.Map<String, String> form
@param java.util.Map<String, String> errors

<form class="${"review-form"}" method="${"post"}" action="${io.suko.generated.SukoSafe.url("/p/" + (productId) + "/reviews")}"><h3>Escrever uma avaliação</h3><input type="${"hidden"}" name="${csrfName}" value="${csrfToken}"/><label>Nome
      <input type="${"text"}" name="${"author"}" value="${form.get("author")}" maxlength="${"80"}"/></label>@if(errors.containsKey("author"))
<p class="${"error"}">${errors.get("author")}</p>
@endif
<label>Classificação, de 1 a 5
      <input type="${"number"}" name="${"rating"}" value="${form.get("rating")}" min="${"1"}" max="${"5"}"/></label>@if(errors.containsKey("rating"))
<p class="${"error"}">${errors.get("rating")}</p>
@endif
<label>Avaliação
      <textarea name="${"body"}" rows="${"5"}" maxlength="${"2000"}">${form.get("body")}</textarea></label>@if(errors.containsKey("body"))
<p class="${"error"}">${errors.get("body")}</p>
@endif
<label>Site do autor, opcional
      <input type="${"text"}" name="${"website"}" value="${form.get("website")}" maxlength="${"200"}"/></label>@if(errors.containsKey("website"))
<p class="${"error"}">${errors.get("website")}</p>
@endif
<button type="${"submit"}">Publicar</button></form>