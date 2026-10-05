package shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Validação de campos de formulário (o servidor é a única validação que conta). */
final class Forms {

    private Forms() {
    }

    /** {@code null} → {@code ""}; espaços nas pontas removidos. */
    static String clean(String s) {
        return s == null ? "" : s.strip();
    }

    /** Comprimento em unidades UTF-16 (igual ou maior que em code points: nunca passa do {@code varchar} da coluna). */
    static boolean lengthBetween(String s, int min, int max) {
        return s.length() >= min && s.length() <= max;
    }

    /** Inteiro em {@code min..max}, ou {@code null} se não é um inteiro nesse intervalo. */
    static Integer intBetween(String s, int min, int max) {
        try {
            int v = Integer.parseInt(clean(s));
            return v >= min && v <= max ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Id positivo; qualquer outra coisa → 400. */
    static long id(String s) {
        try {
            long v = Long.parseLong(clean(s));
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException e) {
            // cai no 400
        }
        throw badRequest();
    }

    static ResponseStatusException badRequest() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
