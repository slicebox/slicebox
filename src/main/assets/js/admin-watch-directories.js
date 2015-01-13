(function () {
   'use strict';
}());

angular.module('slicebox.adminWatchDirectories', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/admin/watchDirectories', {
    templateUrl: '/assets/partials/adminWatchDirectories.html',
    controller: 'AdminWatchDirectoriesCtrl'
  });
})

.controller('AdminWatchDirectoriesCtrl', function($scope, $http) {
  
});