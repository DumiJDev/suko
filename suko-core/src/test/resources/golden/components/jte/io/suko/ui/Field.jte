@param String id
@param String name
@param String label
@param String type = "text"
@param boolean required = false
@param String placeholder = null

<div class="${"space-y-1"}">@template.io.suko.ui.Label(text = label)
@template.io.suko.ui.Input(id = id, name = name, type = type, required = required, placeholder = placeholder)
</div>