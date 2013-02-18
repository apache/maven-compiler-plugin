assert new File( basedir, "target/classes/MyClass.class" ).exists();
assert new File( basedir, "target/test-classes/MyTest.class" ).exists();

content = new File( basedir, 'build.log' ).text;

assert content.contains("Nothing to compile - all classes are up to date");