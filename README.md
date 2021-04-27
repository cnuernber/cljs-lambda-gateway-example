# Shadow CLJS, Reagent, Graal Native, & AWS API Gateway, Lambda


Here is a very basic but complete example of a working shadow-cljs-based application
that works as a standalong server uberjar, standalone executable server, and a lambda
proxy extension using AWS API Gateway.


## Usage

First install the necessary npm packages.

* `npm install`

## Development Commands

Compile clojurescript in dev mode to resources/public/app.js

* `clj -M:cljs watch :app`

Now start a repl and run `(gateway-example.main/start-server)`.  This starts up
a basic web server with a ring handler stack.  This stack servers a homepage and
the required resources.  Note that some of these resources are in resources/public.


At this point you have a working server and you can change the text in webapp and
it should hot-reload.  We can do one better, however, in that we can have boot up
a clojurescript repl.

When shadow-cljs booted up it listed a port - 8777.  Connect to this with your
IDE. You should see a prompt that looks like this:

```clojure
shadow.user>
```

We can query shadow-cljs to find the running apps:

```clojure
shadow.user> (shadow/active-builds)
#{:app}
```

And we can connect to an active build:

```clojure
shadow.user> (shadow/repl :app)
To quit, type: :cljs/quit
[:selected :app]
cljs.user>
```

You can test this with an alert - `(js/alert "hey")`.


## Standalone Uberjar/Executable


* Compile clojurescript in release mode -

```console
rm -rf resources/public/js/* && clj -M:cljs release app
```

This builds app.js, an all-in-one js that bundles everything we are using.

Now build the uberjar

```console
clj -X:standalone-server
```


You should be able to run the server via

```console
java -jar target/standalone.jar
```


Now that we have an uberjar we can compile a graal native executable with this
functionality.  The repo includes three scripts -

* scripts/get-graal - get a linux distribution of graal native compiled with java 11.
* scripts/activate-graal - get graal if user doesn't have it, then export environment
  variable `GRAALVM_HOME` that indicates where graal is installed.  Additionally update
  path such that the graal-installed java, javac, and various graalvm utilites are
  before anything else.
* scripts/compile-standalone - Here is where the magic is.  This command works for Linux
  but I imagine various things need to change for Mac.  Ask in the Clojurians/graal
  slack channel for more information about this script; it packages resources and
  has additions tools for postgres and httpkit's ssl engine as well as enabling
  http,https support.


All you have to do at this point is run:

```console
scripts/compile-standalone
```

Which should result in an executable compiled to target/standalone.  Running
this executable provides the server.

```console
chrisn@chrisn-lt-01:~/dev/cnuernber/cljs-lambda-gateway-example/target$ ./standalone
08:26:33.039 [main] INFO  gateway-example.main - Starting server on port 3000
08:26:33.041 [main] INFO  gateway-example.main - Main function exiting-server still running
```


## AWS API Gateway/Lambda Proxy


### Step 1 - Proxy Lambda

Here things start to get interesting.  We first want to compile the proxy lambda
and upload it to AWS.  You will need credentials in your environment for this step.

AWS Lambda gives you as the developer an interesting option - a 'custom' lambda
runtime is simply an executable script or file named 'bootstrap' in a zip file.
This pares well with graal native but we do need bootstrap to be a script so we
can set java runtime variables; namely -Xmx so we stay within the bounds of our
default lambda memory requirements.

We have a simple build script - scripts/compile-proxy-lambda that builds an uberjar
with the proxy-lambda function and packages it, along with our launch script into
a zipfile named proxy-lambda.zip.

```console
scripts/compile-proxy-lambda
```

Once this script completes you should see target/proxy-lambda.zip is created.


We now need to create an IAM role with the basic lambda execution permission that
we have to attach to our lambda.

* In the AWS console, to to IAM.
* From the left menu, click on Roles.
* Create Role - Lambda - then click Next
* Find the `AWSLambdaBasicExecutionRole` policy and attach it.
* Name your role and finish up.  Get the arn - mine was `arn:aws:iam::801514925221:role/lambda-role`.  It has one policy attached which is the policy listed above.

Now we can upload that script to AWS assuming you have the appropriate credentials
in your environment.

```console
aws lambda create-function --function-name proxy-lambda \
    --zip-file fileb://target/proxy-lambda.zip --handler proxy.handler --runtime provided \
    --role arn:aws:iam::801514925221:role/lambda-role
```

Successful output ends with:

```console
    ...
    "RevisionId": "e5764007-f018-4882-985f-dc8e408c9009",
    "State": "Active",
    "LastUpdateStatus": "Successful"
}
```


You should also be able to see your lambda in your console.

### Step 2 - API Gateway

I wish I had the time to come up with a command line pathway to do this.  The tricky
part of that is the permissions; gateway needs specific access to your lambda
which console does automagically and correctly.  In any case, this part of our
walkthrough now goes back to the console.

##### Setup Base URL

1.  In Console, got to API Gateway.
2.  Find Rest API and click Build.
3.  Click New API and fill out details ensuring you click Regional.  I named mine `proxy-lambda-example`.
4.  This brings you to your API management screen and here lie demons so hang in there.
5.  In the middle panel, click on the single '/' resource.  Choose `Create Method` from the dropdown and a new dropdown appears.  Select 'ANY'.
6.  Now we configure our method to be a proxy lambda method.  Choose 'Lambda' as the type and click the `Use Proxy Lambda Itegration` box and select our
    lambda method (proxy-lambda in my case) as the lambda function to run.

With that, you should see a test screen.  We can test our method - click Test, select Get, and hit Test.  At this point you should see your homepage
in HTML form echoed back to you from the proxy lambda.  New we need to setup additional routes with a wildcard so urls derived from our initial url
also go through our lambda.


##### Deploy Test Stage

1. In the middle pane click Actions.
2. Click `Deploy API`, click 'create stage' name your stage.  The other details are optional.
3. This brings to you Stages where near the top you see a test URL.  Clicking this brings you to
   a blank page.  Our HTML homepage loaded but our resources did not; you can verify this in the
   network tab of your browser dev tools where you see a bunch of 403 error codes.


##### Configure Proxy Wildcard URLs

That still is a large step further but of course we want our entire site to load which involves
more than just hitting the base url.

1.  From the left pane go back to Resources.
2.  From Actions click `Create Resource`.
3.  Click 'configure as proxy resource'
4.  Click 'Create Resource`.
5.  Configure it to use our proxy lambda function.

You should now have *two* methods configured under your base resource.

Now retest with your test url **but make sure to add a '/' at the end**.  For example
in my case https://izo5hfi5d8.execute-api.us-west-2.amazonaws.com/test returns the
same 403 errors while https://izo5hfi5d8.execute-api.us-west-2.amazonaws.com/test/
returns the resources encoded in base-64.  We fix that now.

##### Supporting Binary Resources

There is one more bit of configuration we have to do to our API gateway to support
base64 encoded resources which will be most of the file-based resources such as
js files, css files, and images.

1.  In API Gateway, in leftmost pane click the first Settings.  It is indented a bit.
2.  You should see a set of panels.  A few are interesting but near the end is a panel
    that named `Binary Media Types`. Click `Add Binary Media Type`.
3.  Type in `*/*`.  This simply *allows* all media types to be binary.  Our code in
    proxy_lambda.clj explicitly binary encodes any response bodies that are streams.
4.  Save changes.
5.  Redeploy API.


A *hard reload* (Shift-F5) should reload your page and at this point you should see
our nice welcome screen.


## Addendum

If you make a change to proxy-lambda and you want to update it, run 'compile-proxy-lambda to produce a new zip package and here is the command line to update it:

```console
aws lambda update-function-code  --function-name proxy-lambda \
    --zip-file fileb://target/proxy-lambda.zip
```

It is instructive at this point to check the CloudWatch logs.  Logs for our lambda
are nicely done and since we are careful to write out JSON our logs are quite
readable -- we can get into fancier ways to do structured logging that integrates with
CloudWatch later.  We can also enable Cloudwatch logs for API gateway if we so
choose.
