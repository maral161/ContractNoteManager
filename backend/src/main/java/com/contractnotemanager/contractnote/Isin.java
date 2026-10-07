package com.contractnotemanager.contractnote;

/** ISIN format and check digit (ISO 6166, Luhn over the letter-expanded digits). */
public final class Isin {

    private Isin() {
    }

    public static boolean isValid(String isin) {
        if (isin == null || !isin.matches("[A-Z]{2}[A-Z0-9]{9}[0-9]")) {
            return false;
        }
        StringBuilder digits = new StringBuilder();
        for (char c : isin.substring(0, 11).toCharArray()) {
            digits.append(Character.isDigit(c) ? String.valueOf(c) : String.valueOf(c - 'A' + 10));
        }
        int sum = 0;
        boolean doubleIt = true; // rightmost digit of the payload is doubled
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        int check = (10 - sum % 10) % 10;
        return check == isin.charAt(11) - '0';
    }
}
