# kussx
A URL Shortener Service project for recreation/demo purposes.

This project has the following technological stack:
* Programming language: [Kotlin](https://kotlinlang.org)
* Server Framework(s): 
  * [Vert.x](http://vertx.io): reactive toolkit for the JVM 
  * [RxJava](https://github.com/ReactiveX/RxJava): asynchronous behavior composition library for the JVM 
  * [Redis](https://redis.io): key-value store
* Deployment: [Docker & Docker-Compose](https://docker.com)


## Try it out

Simply run `docker-compose up` and the Rest API shall be available at port 80.


### Rest API
##### `POST /shorten`
Accepts an `application/json` payload in the body that shall contain a field named `url` 
with the value containing the url that shall be shortened.

Replies with a `200 OK` status and a `application/json` payload with a single field named `key` 
with the key that shall be used as a shortcut for the provided url, if everything went OK, 
otherwise a `500 Internal Server Error` is returned.

##### `GET /:key`
Replies with either a `404 Not Found` if the provided key is not found or with `307 Temporary Redirect` 
with the actual url in the `Location` header. 

##### `DELETE /:key`
Deletes the entry associated with the given key.
Replies with a `200 OK` if everything goes smoothly, otherwise a `500 Internal Server Error` is returned.
