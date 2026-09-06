# zed_pkg_client for Elixir

```elixir
client = ZedPkgClient.new(token: System.get_env("ZED_TOKEN"))
{:ok, package} = ZedPkgClient.package(client, "zed-pkg", "zed-clients")
```
