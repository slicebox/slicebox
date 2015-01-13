(function () {
   'use strict';
}());

angular.module('slicebox.view1', ['ngRoute'])

.config(['$routeProvider', function($routeProvider) {
  $routeProvider.when('/view1', {
    templateUrl: '/assets/partials/view1.html',
    controller: 'View1Ctrl'
  });
}])

.controller('View1Ctrl', function($scope, $http) {
  $scope.images = [];

  $http.get('/api/metadata/allimages').success(function(data) {
    $scope.images = data;
  });
});