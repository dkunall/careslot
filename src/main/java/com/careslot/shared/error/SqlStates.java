package com.careslot.shared.error;

import java.sql.SQLException;

/** Finds a Postgres SQLSTATE anywhere in an exception's cause chain. */
public final class SqlStates {

    public static final String EXCLUSION_VIOLATION = "23P01";
    public static final String UNIQUE_VIOLATION = "23505";

    private SqlStates() {
    }

    public static boolean is(Throwable e, String state) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && state.equals(s.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
