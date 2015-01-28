(function () {
   'use strict';
}());

angular.module('slicebox.home', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/', {
    templateUrl: '/assets/partials/home.html',
    controller: 'HomeCtrl'
  });
})

.controller('HomeCtrl', function($scope, $http) {
    // Initialization
    $scope.callbacks = {};

    $scope.uiState = {
        errorMessage: null
    };

    // Scope functions
    $scope.loadPatients = function(startIndex, count, orderByProperty, orderByDirection) {
        return $http.get('/api/metadata/patients');
    };

    // $scope.convertPatientsPageData = function(patients) {
    //     angular.forEach(patients, function(patient) {
    //         angular.forEach(patient, function(propertyValue, propertyName) {
    //             patient[propertyName] = propertyValue.value;
    //         });
    //     });

    //     return patients;
    // };

    $scope.closeErrorMessageAlert = function() {
        $scope.uiState.errorMessage = null;
    };
});