import lombok.ExtensionMethod;
import java.util.Arrays;
@ExtensionMethod({Arrays.class, ExtensionMethodPlain.Objects.class, ExtensionMethodPlain.Strings.class}) class ExtensionMethodPlain {
  private static class ExtensionMethodInExplicitSuperCall extends Exception {
    public ExtensionMethodInExplicitSuperCall() {
      super(ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r"));
      ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r");
    }
  }
  static class Objects {
    Objects() {
      super();
    }
    public static boolean isOneOf(Object object, Object... possibleValues) {
      if ((possibleValues != null))
          for (Object possibleValue : possibleValues) 
            {
              if (object.equals(possibleValue))
                  return true;
            }
      return false;
    }
  }
  static class Strings {
    Strings() {
      super();
    }
    public static boolean matchesIgnoreCase(String s, String p) {
      return false;
    }
    public static String escapeToJavaRegex(String s) {
      return s;
    }
  }
  private static final String s = ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r");
  static {
    final String staticInitializerVar = ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r");
  }
  {
    final String initializerVar = ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r");
  }
  <clinit>() {
  }
  ExtensionMethodPlain() {
    super();
  }
  private void test1() {
    new Runnable() {
      x() {
        super();
      }
      public @Override void run() {
        long[] values = new long[]{2, 5, 7, 9};
        java.util.Arrays.sort(java.util.Arrays.copyOf(values, 3));
      }
    };
  }
  private boolean test2(String s) {
    return ExtensionMethodPlain.Objects.isOneOf(s, "for", "bar");
  }
  private boolean test3() {
    try 
      {
        return ExtensionMethodPlain.Objects.isOneOf(this, "for", "bar");
      }
    catch (Exception e)       {
        throw new RuntimeException(ExtensionMethodPlain.Strings.escapeToJavaRegex("f?ob*r"));
      }
  }
  private boolean test4(String s) {
    return ExtensionMethodPlain.Objects.isOneOf(Objects, s, "for", "bar");
  }
  private boolean test5(final Iterable<String> paths, final String path) {
    for (final String p : paths) 
      {
        if (ExtensionMethodPlain.Strings.matchesIgnoreCase(path, ExtensionMethodPlain.Strings.escapeToJavaRegex(p)))
            {
              return true;
            }
      }
    return false;
  }
}