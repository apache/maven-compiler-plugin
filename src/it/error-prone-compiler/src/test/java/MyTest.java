import junit.framework.TestCase;

import java.util.*;

public class MyTest
    extends TestCase
{

    public boolean bug2() {
        //BUG: Suggestion includes "return false"
        return new ArrayList<String>().remove(new Date());
    }

}
