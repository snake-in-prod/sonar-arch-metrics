package app.u;

class Unused {
  private int usedField;
  private int unusedField;
  int pkgField;
  int pkgUnusedField;
  private static final long serialVersionUID = 1L;

  private void usedPrivate() {
  }

  private void unusedPrivate() {
  }

  void pkgUsed() {
  }

  void pkgUnused() {
  }

  public void api() {
    usedPrivate();
    int local = usedField;
    int dead = 0;
    System.out.println(local);
    for (String s : new String[0]) {
      System.out.println();
    }
  }

  private static class UnusedNested {
  }

  private static class UsedNested {
  }

  public Object make() {
    return new UsedNested();
  }
}
