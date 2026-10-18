// Suko — funções de apoio do JS gerado (subprojeto 13b). Reproduzem a semântica de Java que o tradutor
// garante: null visível (TypeError, como a NullPointerException), int de 32 bits, divisão por zero
// (RangeError, como a ArithmeticException), Double.toString, String.trim, índices verificados. Sem DOM,
// sem estado global, sem dependências. O plano 2 junta este módulo ao suko.js gerado; não editar à mão.

export function nn(v) {
  if (v === null || v === undefined) {
    throw new TypeError("null");
  }
  return v;
}

export function idiv(a, b) {
  if (b === 0) {
    throw new RangeError("/ by zero");
  }
  return Math.trunc(a / b) | 0;
}

export function imod(a, b) {
  if (b === 0) {
    throw new RangeError("/ by zero");
  }
  return (a % b) | 0;
}

// Double.toString do JDK (>= 19): os dígitos mais curtos (os de Number.prototype.toString), com pelo menos
// dois quando o mais curto tem um e há um de dois mais próximo; notação simples em [1e-3, 1e7), senão
// d.dddE<n>.
export function dstr(d) {
  if (Number.isNaN(d)) {
    return "NaN";
  }
  if (d === Infinity) {
    return "Infinity";
  }
  if (d === -Infinity) {
    return "-Infinity";
  }
  if (d === 0) {
    return Object.is(d, -0) ? "-0.0" : "0.0";
  }
  const negative = d < 0;
  const a = negative ? -d : d;
  const [mantissa, exponent] = a.toExponential().split("e");
  let digits = mantissa.replace(".", "");
  let exp = Number(exponent);
  if (digits.length === 1) {
    const two = Number(a.toPrecision(2));
    if (two === a) {
      const [m2, e2] = two.toExponential(1).split("e");
      const d2 = m2.replace(".", "");
      if (d2[1] !== "0") {
        digits = d2;
        exp = Number(e2);
      }
    }
  }
  digits = digits.replace(/0+$/, "");
  if (digits === "") {
    digits = "0";
  }
  let out;
  if (a >= 1e-3 && a < 1e7) {
    const point = exp + 1;
    if (point <= 0) {
      out = "0." + "0".repeat(-point) + digits;
    } else if (point >= digits.length) {
      out = digits + "0".repeat(point - digits.length) + ".0";
    } else {
      out = digits.slice(0, point) + "." + digits.slice(point);
    }
  } else {
    out = digits[0] + "." + (digits.length > 1 ? digits.slice(1) : "0") + "E" + exp;
  }
  return (negative ? "-" : "") + out;
}

// Concatenação de Java com um double (null → "null").
export function dcat(v) {
  return v === null || v === undefined ? "null" : dstr(v);
}

// Texto de um nó, como o JTE o escreve: null → "".
export function text(v) {
  return v === null || v === undefined ? "" : String(v);
}

export function dtext(v) {
  return v === null || v === undefined ? "" : dstr(v);
}

// String.valueOf de Java para a entrada dos portes do SukoSafe, mas com null → null (o porte decide).
export function sval(v) {
  return v === null || v === undefined ? null : String(v);
}

export function dval(v) {
  return v === null || v === undefined ? null : dstr(v);
}

// String.trim de Java: tira as unidades <= U+0020 nas pontas (nunca String.prototype.trim()).
export function trim(s) {
  nn(s);
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) {
    start++;
  }
  while (end > start && s.charCodeAt(end - 1) <= 0x20) {
    end--;
  }
  return s.substring(start, end);
}

export function contains(s, t) {
  return nn(s).includes(nn(t));
}

export function startsWith(s, t) {
  return nn(s).startsWith(nn(t));
}

export function endsWith(s, t) {
  return nn(s).endsWith(nn(t));
}

export function equals(s, o) {
  return nn(s) === o;
}

function index(list, i) {
  nn(list);
  if (!Number.isInteger(i) || i < 0 || i >= list.length) {
    throw new RangeError("Index " + i + " out of bounds for length " + list.length);
  }
  return i;
}

export function get(list, i) {
  return list[index(list, i)];
}

export function removeAt(list, i) {
  return list.splice(index(list, i), 1)[0];
}

export function setAt(list, i, v) {
  const at = index(list, i);
  const old = list[at];
  list[at] = v;
  return old;
}

export function clear(list) {
  nn(list).length = 0;
}

export function mget(m, k) {
  nn(m);
  return m.has(k) ? m.get(k) : null;
}

export function mhas(m, k) {
  return nn(m).has(k);
}

// Acessor de record: só propriedades próprias (constructor, __proto__ e toString nunca vêm do protótipo).
export function field(o, k) {
  nn(o);
  if (!Object.hasOwn(o, k)) {
    throw new TypeError("componente de record em falta: " + k);
  }
  return o[k];
}

// new Record(...): objeto sem protótipo e imutável, como um record.
export function rec(names, values) {
  const o = Object.create(null);
  for (let i = 0; i < names.length; i++) {
    o[names[i]] = values[i];
  }
  return Object.freeze(o);
}

export function evValue(e) {
  const t = nn(e).target;
  return t === null || t === undefined || t.value === null || t.value === undefined ? null : String(t.value);
}

export function evChecked(e) {
  const t = nn(e).target;
  return t !== null && t !== undefined && t.checked === true;
}

export function evKey(e) {
  return typeof nn(e).key === "string" ? e.key : null;
}

export function evPrevent(e) {
  nn(e).preventDefault();
}
