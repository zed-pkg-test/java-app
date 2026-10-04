# zed_pkg_client for Ruby

A dependency-free Ruby 3 client for the core zed-pkg registry lifecycle.

```ruby
require "zed_pkg_client"
client = ZedPkg::Client.new(token: ENV["ZED_TOKEN"])
puts client.package("zed-pkg", "zed-clients")
```
