## introduction
the repo is aimed to provide a common actor and future api to cache data in application. now, it is readonly and no eviction support.  
## components
### 1. CacheActor
defines the behavior to hold cached data in an actor.
### 2. CacheFutureApi
the actor api is not suggested because there are some disadvantages for actors. for example, actors are not easy to compose.
we provide future api to interact with the actor.
### 3. RefreshPolicy
the policy defines how your actor refreshes data. now, we have 3 policies:
1. NoRefresh. the actor will start with an empty data you provide and there is no refresh in the whole life cycle.
2. ReloadAndUpdate. the actor will load data with method you provide and reload it periodically. And the actor will use the update method you provide to update the cached data when reload succeed.
3. LoadAndKeep. the actor will load data at the startup but will never reload it again.
### 4. MissedPolicy
the policy define how your actor' behavior when there is no data for a request. now, we have 2 policies:
1. JustReturnNone. when we cannot found the cached data for a request, just return none for that request.
2. FetchMissedAndUpdate. when we cannot found the cached data for a request, the actor will try to fetch the missed data with the method you provide and update the cached data once fetched.
### 5. EvictionPolicy
not support yet
## api
### 1. actor api
there is only one method you can use to create a behavior:
```scala
def apply[K,V,C](getter: (C,K) => Option[V],refreshPolicy: RefreshPolicy[C], missedPolicy: MissedPolicy[K,V,C]):Behavior[Command[K,V,C]]
```
for type parameter:
* `K` is the type of your key to access cached data. 
* `V` is the type of the data you want to get from the actor.
* `C` is the type of cached data. this is what you keep in the actor.
for value parameter:
* `getter` is the method how you get value with key from cached data.
* `refreshPolicy`: already introduced.
* `missedPolicy`: already introduced.
### 2. future api
in future api, we will create an actor inside and there are several abstract method in future api:
```scala
  protected def context:ActorContext[_]
  protected def actorName:String
  protected def refreshPolicy:RefreshPolicy[C]
  protected def missedPolicy:MissedPolicy[K,V,C]
  protected def getFromLocal(c: C,k: K):Option[V]
```
there are too straightforward to be explained.

when all these abstract method are implemented. you will get a method:
```scala
 def get(k: K)(implicit deadline:Deadline):Future[Option[V]]
```
## 用例
假设我们需要从数据库里加载一些用户的信息。
```scala
case class User(id:String,otherInfo:Any)
def loadUserFromDB(id:String):Future[Option[User]] = ???
``` 
### 1. 空数据启动，按需缓存
当用户数据过多，无法有一次全部加载到内存，并且，当某个用户的信息被加载过一次之后，其在之后的一段时间会被频繁的访问时。
我们可以是使用空数据启动actor，之后每次先检查缓存，如果未命中，再去数据库中加载，加载完之后更新缓存。

refreshPolicy: NoRefresh

missedPolicy: FetchMissedAndUpdate

注意事项：
1. 目前还没有实现EvictionPolicy，数据不能被回收， 如果数据量过大，可能会内存溢出。
2. 程序启动后，缓存的数据无法变更。这个问题也可以通过EvictionPolicy解决。
### 2. 启动加载，定期更新
当用户数据较少，且变更不是很频繁时，可以使用此种策略。

refreshPolicy： ReloadAndUpdate

missedPolicy： JustReturnNone

```scala
def loadAllUsersFromDB():Future[Seq[UserInfo]] = ???
```
actor api:
```scala
val getter:(Map[String,User],String) => Option[User] = _.get(_)
  val resfreshPolicy:RefreshPolicy[Map[String,User]] = ReloadAndUpdate(
    interval = 10.minutes,
    loader= () => loadAllUsersFromDB().map(users => users.map(user => user.id -> user).toMap),
    updater = (oldCache,newCache) => newCache,
    initStashCapacity = 1000
  )
  val missedPolicy = JustReturnNone[String,User,Map[String,User]]()
  val userActor = ActorSystem(
    CacheActor[String,User,Map[String,User]](getter,resfreshPolicy,missedPolicy),
    "user-cache-actor"
  )
```
future api:
```scala
trait UserOps{
    def getUser(id:String)(implicit deadline: Deadline):Future[Option[User]]
  }

  class CachedUserOpsImpl(override val context:ActorContext[_]) extends UserOps with CacheFutureApi[String,User,Map[String,User]]{
    private def actorName:String = "user-cache-actor"
    private def refreshPolicy:RefreshPolicy[Map[String,User]] = ReloadAndUpdate(
      interval = 10.minutes,
      loader= () => loadAllUsersFromDB().map(users => users.map(user => user.id -> user).toMap),
      updater = (oldCache,newCache) => newCache,
      initStashCapacity = 1000
    )
    private def missedPolicy:MissedPolicy[String,User,Map[String,User]] = JustReturnNone[String,User,Map[String,User]]()
    private def getFromLocal(c: Map[String,User],k: String):Option[User] = c.get(k)

    override def getUser(id: String)(implicit deadline: Deadline): Future[Option[User]] = get(id)
  }
```



