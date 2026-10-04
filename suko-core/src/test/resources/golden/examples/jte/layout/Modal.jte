@param String title
@param boolean open
@param gg.jte.Content header
@param gg.jte.Content body
@param gg.jte.Content footer

@if(open)
<div class="${"modal-overlay"}"><div class="${"modal"}"><div class="${"modal-header"}"><h2>${title}</h2>${header}</div><div class="${"modal-body"}">${body}</div><div class="${"modal-footer"}">${footer}</div></div></div>
@endif
