@param String href = "#"
@param String label
@param String variant = "primary"
@param boolean disabled = false

<a href="${io.suko.generated.SukoSafe.url(href)}" class="${"btn btn-" + (variant)}" disabled="${disabled}">${label}</a>