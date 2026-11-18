package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

/** JVM-only mastered recording identity; the Android unit-test Uri.parse stub returns null. */
public final class LoopFixtureUri extends Uri {
    private final String id;
    public LoopFixtureUri(String id) { this.id = id; }
    @Override public String getQueryParameter(String key) { return "v".equals(key) ? id : null; }
    @Override public String getScheme() { return "https"; }
    @Override public String getAuthority() { return "loop-fixture.invalid"; }
    @Override public String getEncodedAuthority() { return getAuthority(); }
    @Override public String getHost() { return getAuthority(); }
    @Override public int getPort() { return -1; }
    @Override public String getPath() { return "/" + id; }
    @Override public String getEncodedPath() { return getPath(); }
    @Override public List<String> getPathSegments() { return Collections.singletonList(id); }
    @Override public String getLastPathSegment() { return id; }
    @Override public String getQuery() { return "v=" + id; }
    @Override public String getEncodedQuery() { return getQuery(); }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public String getSchemeSpecificPart() { return "//" + getAuthority() + getPath() + "?" + getQuery(); }
    @Override public String getEncodedSchemeSpecificPart() { return getSchemeSpecificPart(); }
    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }
    @Override public Builder buildUpon() { throw new UnsupportedOperationException("immutable fixture"); }
    @Override public String toString() { return "https:" + getSchemeSpecificPart(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel parcel, int flags) { throw new UnsupportedOperationException("no parcels"); }
}
