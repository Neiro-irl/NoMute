package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

/** Minimal opaque content Uri for repository JVM tests; it does not emulate Android Uri parsing. */
public final class TestUri extends Uri {
    private final String value;
    private final String scheme;

    public TestUri(String value) {
        this(value, "content");
    }

    public TestUri(String value, String scheme) {
        super();
        this.value = value;
        this.scheme = scheme;
    }

    @Override public Builder buildUpon() { throw new UnsupportedOperationException(); }
    @Override public String getAuthority() { return null; }
    @Override public String getEncodedAuthority() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getEncodedPath() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getEncodedSchemeSpecificPart() { return value; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getHost() { return null; }
    @Override public String getLastPathSegment() { return null; }
    @Override public String getPath() { return null; }
    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
    @Override public int getPort() { return -1; }
    @Override public String getQuery() { return null; }
    @Override public String getScheme() { return scheme; }
    @Override public String getSchemeSpecificPart() { return value; }
    @Override public String getUserInfo() { return null; }
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public String toString() { return value; }
    @Override public boolean equals(Object other) {
        return other instanceof Uri && toString().equals(other.toString());
    }
    @Override public int hashCode() { return toString().hashCode(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel destination, int flags) { throw new UnsupportedOperationException(); }
}
