import org.testng.Assert;
import org.testng.annotations.Test;

// Task 3: deterministic failure used to prove retryOnFailure retries. Needs no Selenium
// session, so every retry attempt fails fast instead of burning a browser session.
public class TestIntentionalFailure {

    @Test(description = "Intentional hard failure to validate HyperExecute retryOnFailure")
    public void test_intentional_failure() {
        Assert.assertEquals(1, 2, "Intentional failure - used to verify retryOnFailure behavior");
    }
}
