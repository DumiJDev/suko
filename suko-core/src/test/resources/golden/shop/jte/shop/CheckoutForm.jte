@param String csrfName
@param String csrfToken
@param java.util.Map<String, String> form
@param java.util.Map<String, String> errors

<form class="${"checkout-form"}" method="${"post"}" action="${"/checkout"}"><input type="${"hidden"}" name="${csrfName}" value="${csrfToken}"/><label>Nome
      <input type="${"text"}" name="${"name"}" value="${form.get("name")}" maxlength="${"120"}" autocomplete="${"name"}"/></label>@if(errors.containsKey("name"))
<p class="${"error"}">${errors.get("name")}</p>
@endif
<label>E-mail
      <input type="${"email"}" name="${"email"}" value="${form.get("email")}" maxlength="${"200"}" autocomplete="${"email"}"/></label>@if(errors.containsKey("email"))
<p class="${"error"}">${errors.get("email")}</p>
@endif
<label>Morada
      <textarea name="${"address"}" rows="${"3"}" maxlength="${"400"}" autocomplete="${"street-address"}">${form.get("address")}</textarea></label>@if(errors.containsKey("address"))
<p class="${"error"}">${errors.get("address")}</p>
@endif
<button type="${"submit"}">Confirmar encomenda</button></form>