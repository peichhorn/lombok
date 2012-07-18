class ExtensionMethodGenericArray {
	
	private void test7() {
		String[] foo = null;
		String[] s = ExtensionMethodGenericArray.Objects.orElse(foo, new String[0]);
	}
	
	static class Objects {
		public static <T> T[] orElse(T[] value, T[] orElse) {
			return value == null ? orElse : value;
		}
	}
}